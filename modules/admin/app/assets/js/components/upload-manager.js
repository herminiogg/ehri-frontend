"use strict";

class UploadCancelled extends Error {
  constructor(fileName) {
    super("Upload cancelled: " + fileName);
    this.name = "UploadCancelled";
  }
}

/**
 * Sequentially invoke an upload function. Cancellation of individual
 * files or the whole batch is handled via throwing an {{UploadCancelled}}
 * error which is caught and increases the cancellation state passed to the
 * next invocation.
 *
 * @param uploadFunc  a function to invoke to upload a file
 * @param argArray    an array of {{File}} objects
 * @param index       the index to the {{argArray}} item to upload
 * @param done        the number of files uploaded in this batch
 * @param cancelled   the number of cancellations in this batch
 * @returns {*|Promise<{cancelled: *, done: *}>}
 */
function sequentialUpload(uploadFunc, argArray, index, {done, cancelled}) {
  if (index >= argArray.length) return Promise.resolve({done, cancelled});
  return uploadFunc(argArray[index])
    .then(() => sequentialUpload(uploadFunc, argArray, index + 1, {
      done: done + 1, cancelled
    }))
    .catch(e => {
      if (e instanceof UploadCancelled) {
        return Promise.resolve(sequentialUpload(uploadFunc, argArray, index + 1, {
          done,
          cancelled: cancelled + 1
        }));
      } else {
        throw e;
      }
    });
}

Vue.component("upload-progress", {
  props: {
    uploading: Array,
  },
  data: function() {
    return {
      showProgress: true,
    };
  },
  template: `
    <div v-if="uploading.length > 0" class="upload-progress-container">
      <div class="upload-progress-title">
        <div v-on:click.prevent="showProgress = !showProgress" class="close">
          <i class="fa fa-window-restore"></i>
        </div>
      </div>
      <div v-if="showProgress" class="upload-progress">
        <div v-for="job in uploading" v-bind:key="job.spec.name" class="progress-container">
          <div class="progress">
            <div class="progress-bar progress-bar-striped progress-bar-animated"
                 role="progressbar"
                 v-bind:aria-valuemax="100"
                 v-bind:aria-valuemin="0"
                 v-bind:aria-valuenow="job.progress"
                 v-bind:style="'width: ' + job.progress + '%'">
              {{ job.spec.name}}
            </div>
          </div>
          <button class="btn btn-sm btn-default cancel-button" v-on:click.prevent="$emit('finish-item', job.spec)">
            <i class="fa fa-fw fa-times-circle"/>
          </button>
        </div>
      </div>
      <div class="upload-progress-controls">
        <div class="btn btn-sm btn-default" v-on:click="$emit('cancel-upload')">
          <i class="fa fa-fw fa-spin fa-circle-o-notch"></i>
          Cancel Uploads
        </div>
      </div>
    </div>
  `
});

Vue.component("upload-manager", {
  mixins: [stageMixin, twoPanelMixin, previewMixin, validatorMixin, errorMixin, utilMixin],
  props: {
    datasetId: String,
    fileStage: String,
    config: Object,
    api: DAO,
  },
  data: function () {
    return {
      dropping: false,
      uploading: [],
      cancelled: [],
    }
  },
  methods: {
    finishUpload: function (fileSpec) {
      this.setUploadProgress(fileSpec, 100);
      setTimeout(() => {
        let i = _.findIndex(this.uploading, s => s.spec.name === fileSpec.name);
        this.uploading.splice(i, 1)
      }, 1000);
    },
    setUploadProgress: function (fileSpec, percent) {
      let i = _.findIndex(this.uploading, s => s.spec.name === fileSpec.name);
      if (i > -1) {
        this.uploading[i].progress = Math.min(100, percent);
        return true;
      }
      return false;
    },
    finishAllUploads: function() {
      this.uploading = [];
    },
    dragOver: function () {
      this.dropping = true;
    },
    dragLeave: function () {
      this.dropping = false;
    },
    uploadFile: function (file) {
      // Check we're still in the queue and have not been cancelled...
      if (_.findIndex(this.uploading, f => f.spec.name === file.name) === -1) {
        return Promise.reject(new UploadCancelled(file.name));
      }

      return this.api.uploadHandle(this.datasetId, this.fileStage, {
        name: file.name,
        type: file.type,
        size: file.size
      })
        .then(data => {
          let self = this;
          this.setUploadProgress(file, 0);
          return this.api.uploadFile(data.presignedUrl, file, function (evt) {
            return evt.lengthComputable
              ? self.setUploadProgress(file, Math.round((evt.loaded / evt.total) * 100))
              : true;
          })
            .then(() => {
              this.finishUpload(file);
              this.log.push("Uploaded file: " + file.name);
              this.refresh();
              return file;
            });
        })
        .catch(error => this.showError("Upload error", error));
    },
    uploadFiles: function (event) {
      this.dragLeave(event);
      this.tab = 'upload';
      let self = this;

      let fileList = event.dataTransfer
        ? event.dataTransfer.files
        : event.target.files;

      let files = [];
      for (let i = 0; i < fileList.length; i++) {
        let file = fileList[i];
        if (file.type === "text/xml") {
          this.uploading.push({
            spec: file,
            progress: 0,
          });
          files.push(file);
        }
      }

      // Files were dropped but there were no file ones
      if (files.length === 0 && fileList.length > 0) {
        return Promise.reject("No valid files found")
      }

      // Nothing is selected: no-op
      if (files.length === 0) {
        return Promise.resolve();
      }

      // Proceed with upload
      return sequentialUpload(self.uploadFile, files, 0, {done: 0, cancelled: 0})
        .then(({done, cancelled}) => {
          if (event.target.files) {
            // Delete the value of the control, if loaded
            event.target.value = null;
          }

          this.log.push("Uploaded: " + done + (cancelled ? (", Cancelled: " + cancelled) : ""))
        });
    },
  },
  template: `
    <div id="upload-manager-container" class="stage-manager-container"
         v-on:dragover.prevent.stop="dragOver"
         v-on:dragleave.prevent.stop="dragLeave"
         v-on:drop.prevent.stop="uploadFiles">

      <div class="actions-bar">
        <filter-control v-bind:filter="filter"
                        v-on:filter="filterFiles"
                        v-on:clear="clearFilter"/>
        
        <button v-bind:disabled="files.length===0" class="btn btn-sm btn-default"
                v-on:click.prevent="validateFiles(selectedKeys)" v-if="selectedKeys.length">
          <i class="fa fa-flag-o"/>
          Validate Selected ({{selectedKeys.length}})
        </button>
        <button v-bind:disabled="files.length===0" class="btn btn-sm btn-default"
                v-on:click.prevent="validateFiles(files.map(f => f.key))" v-else>
          <i class="fa fa-flag-o"/>
          Validate All
        </button>

        <button v-bind:disabled="files.length===0" class="btn btn-sm btn-default"
                v-on:click.prevent="deleteFiles(selectedKeys)" v-if="selectedKeys.length > 0">
          <i class="fa fa-trash-o"/>
          Delete Selected ({{selectedKeys.length}})
        </button>
        <button v-bind:disabled="files.length===0" class="btn btn-sm btn-default" v-on:click.prevent="deleteAll()"
                v-else>
          <i class="fa fa-trash-o"/>
          Delete All
        </button>

        <button class="file-upload-button btn btn-sm btn-default">
          <input class="file-selector-input"
                 v-on:change="uploadFiles"
                 v-on:dragover.prevent="dragOver"
                 v-on:dragleave.prevent="dragLeave"
                 v-on:drop.prevent="uploadFiles"
                 type="file"
                 accept="text/xml" multiple/>
          <i class="fa fa-cloud-upload"/>
          Upload Files...
        </button>
      </div>

      <div id="upload-panel-container" class="panel-container">
        <div class="top-panel">
          <files-table
            v-bind:api="api"
            v-bind:fileStage="fileStage"
            v-bind:dropping="dropping"
            v-bind:loaded="loaded"
            v-bind:loading-more="loadingMore"
            v-bind:files="files"
            v-bind:selected="selected"
            v-bind:previewing="previewing"
            v-bind:validating="validating"
            v-bind:validationResults="validationResults"
            v-bind:truncated="truncated"
            v-bind:deleting="deleting"
            v-bind:downloading="downloading"
            v-bind:ingesting="null"
            v-bind:filter="filter.value"

            v-on:delete-files="deleteFiles"
            v-on:download-files="downloadFiles"
            v-on:validate-files="validateFiles"
            v-on:load-more="loadMore"
            v-on:show-preview="showPreview"
            v-on:select-next="selectNext"
            v-on:select-prev="selectPrev"
            v-on:deselect-all="deselect"
          />
        </div>

        <div id="upload-status-panel" class="bottom-panel">
          <ul class="status-panel-tabs nav nav-tabs">
            <li class="nav-item">
              <a href="#" class="nav-link" v-bind:class="{'active': tab === 'preview'}"
                 v-on:click.prevent="tab = 'preview'">
                File Preview
                <template v-if="previewing"> - {{previewing.key}}</template>
              </a>
            </li>
            <li class="nav-item">
              <a href="#" class="nav-link" v-bind:class="{'active': tab === 'validation'}"
                 v-on:click.prevent="tab = 'validation'">
                Validation Log
              </a>
            </li>
            <li class="nav-item">
              <a href="#" class="nav-link" v-bind:class="{'active': tab === 'upload'}"
                 v-on:click.prevent="tab = 'upload'">
                Upload Log
              </a>
            </li>
            <li>
              <drag-handle
                v-bind:ns="fileStage"
                v-bind:p2="$root.$el.querySelector('#upload-status-panel')"
                v-bind:container="$root.$el.querySelector('#upload-panel-container')"
                v-on:resize="setPanelSize"
              />
            </li>
          </ul>

          <div class="status-panels">
            <div class="status-panel" v-show="tab === 'preview'">
              <preview v-bind:dataset-id="datasetId"
                       v-bind:file-stage="fileStage"
                       v-bind:previewing="previewing"
                       v-bind:panel-size="panelSize"
                       v-bind:config="config"
                       v-bind:api="api"
                       v-on:error="showError"
                       v-show="previewing !== null" />
              <div class="panel-placeholder" v-if="previewing === null">
                No file selected.
              </div>
            </div>
            <div class="status-panel log-container" v-show="tab === 'validation'">
              <log-window v-bind:log="validationLog" v-if="validationLog.length > 0"/>
              <div id="validation-placeholder" class="panel-placeholder" v-else>
                Validation log output will show here.
              </div>
            </div>
            <div class="status-panel log-container" v-show="tab === 'upload'">
              <log-window v-bind:log="log" v-if="log.length > 0"/>
              <div class="panel-placeholder" v-else>
                Upload log output will show here.
              </div>
            </div>
          </div>
        </div>

      </div>

      <upload-progress
        v-bind:uploading="uploading"
        v-on:finish-item="finishUpload"
        v-on:cancel-upload="finishAllUploads" />
    </div>
  `
});
