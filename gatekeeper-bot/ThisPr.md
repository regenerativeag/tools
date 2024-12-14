Done:
- deserialize new rules from config

TODO define rules & logic:
- define all rules
- re-implement "recalculate roles" functionality
- add new path for special "reacted" role
- update tests with testing roles using new rules, ensure logic works correctly
- new test for special "reacted" code path

TODO define actual roles:
- create new quiet-garden room in discord (not yet configured, just need channelId)
- use manual tests to create message in quiet-garden (not yet fully edited, just need messageId)
- create new roles in discord (unconfigured.. just need roleIds for now)
- define config for new roles
- update tests which rely on actual roles
- all tests should pass now

TODO cleanup & dry-run:
- look for any remaining "TODO #26" todos
- any refactoring
- configure new roles and quiet-garden in discord
- extensive testing
  - dry run
  - does daily job reset work done by new code path that promotes users based on reacting?
  - can I move in and out of quiet garden as I should be able to?
  - Test visibility of rooms & other configuration of roles in discord

Announce, deploy, any bug fixes!