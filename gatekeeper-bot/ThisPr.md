✅ Pre-requisites:
- ✅ Record required data in DB

Define rules & logic:
- ✅ Deserialize new rules from config
- ✅ Define all rules
- re-implement "recalculate roles" functionality
  - Define such that there's a single function when called from on_react(), on_post(), or daily_reset()
  - Nullable "Action" parameter
- ✅ call from on_react()
- update tests with "test" roles. Ensure rule logic works correctly
- new test for special "reacted" code path

Define actual roles:
- create new quiet-garden room in discord (not yet configured, just need channelId)
- use manual tests to create message in quiet-garden (not yet fully edited, just need messageId)
- create new roles in discord (unconfigured.. just need roleIds for now)
- ✅ Define config for new roles
- update tests which rely on actual roles and make sure every single path between roles is tested
- all tests should pass now

Cleanup & dry-run:
- look for any remaining "TODO #26" todos
- any refactoring
- configure new roles and quiet-garden in discord
- any other paths between roles we discussed that aren't defined in the config?
- extensive testing
  - dry run
  - does daily job reset work done by new code path that promotes users based on reacting?
  - can I move in and out of quiet garden as I should be able to?
  - Test visibility of rooms & other configuration of roles in discord
- Delete this file

Announce, deploy, any bug fixes!