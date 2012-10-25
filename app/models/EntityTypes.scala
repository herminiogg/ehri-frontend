package models

object EntityTypes extends Enumeration() {
  type Type = Value
  val DocumentaryUnit = Value("documentaryUnit")
  val Agent = Value("agent")
  val Action = Value("action")
  val UserProfile = Value("userProfile")
  val Group = Value("group")
  val DocumentaryUnitDescription = Value("documentDescription")
  val AgentDescription = Value("agentDescription")
  val AuthorityDescription = Value("authorityDescription")
}
