INSERT INTO import_dataset (repo_id, id, name, type, comments)
VALUES
        ('r1', '1', '1', 'urlset', 'test 1'),
        ('r1', '2', '2', 'urlset', 'test 2'),
        ('r1', '3', '3', 'urlset', 'test 3');

INSERT INTO import_url_set_config
VALUES
        ('r1', '1', '[["http://example.com", "test"]]'::jsonb, NULL, '2020-06-12 10:00:00', NULL),
        ('r1', '2', '[["http://example.com", "test"]]'::jsonb, '[["Accept", "text/xml"]]'::jsonb, '2020-06-12 10:00:00', NULL);
