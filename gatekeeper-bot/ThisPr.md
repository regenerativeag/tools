✅ Pre-requisites:
- ✅ Record required data in DB

Define rules & logic:
- ✅ Deserialize new rules from config
- ✅ Define all rules
- ✅ re-implement "recalculate roles" functionality
  - ✅ Define such that there's a single function when called from on_react(), on_post(), or daily_reset()
  - ✅ Nullable "Action" parameter
  - ✅ Evaluate various rules
  - ✅ Gather required info before calling
- ✅ call from on_react()
- Update tests using real config:
  - Update MembershipRoleServiceTest testing addOrRemoveRoles(qualification, userIds) -> Unit
  - Create new MembershipRoleDeterminationTest testing determineMembershipRole() -> Qualification
- new test for special "reacted" code path

Define actual roles:
- ✅ create new quiet-garden room in discord
- ✅ create message in quiet-garden
- ✅ create new roles in discord (unconfigured.. just need roleIds for now)
- ✅ replace Ids in config & const file
- ✅ Define config for new roles
- all tests should pass now

Cleanup & dry-run:
- look for any remaining "TODO #26" todos
- any refactoring
- ✅ configure new roles and quiet-garden in discord
- any other paths between roles we discussed that aren't defined in the config?
- ✅ manual testing
  - ✅ dry run
  - ✅ can I move in and out of quiet garden as I should be able to?
  - ✅ Test visibility of rooms & other configuration of roles in discord
- Delete this file

✅ Announce

✅ Deploy

Bug fixes
- ✅ User bounces between Member and Active Member if they have not posted in the last 30 days, but still meet the "8 days 4 weeks" rule
- any more?