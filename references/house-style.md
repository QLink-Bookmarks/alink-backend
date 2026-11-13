# House Style

Rules the code on `main` shows but no other reference states. Read this before writing or reviewing any change under `src/main`, together with `references/domain-review.md`. Each rule names the review that set it, so the reason travels with the rule.

The principle: `main` is the specification for everything the written rules leave open. A new file looks like the file next to it, and a new behavior lives where the same behavior already lives.

## Precedent

- **H1** Before adding a type, find its closest sibling on `main` (same package, same kind: service, route, repository, table, worker, client, DI module, test) and copy its shape: file layout, naming, constructor order, DI registration, repository and table style, test style. A rule in this file or in `domain-review.md` wins over a sibling; between two siblings, the newer one wins. An older file that breaks a rule in this file is not precedent.
- **H2** Extend before adding. A use case that already exists on `main` in another mode (audience, platform, scope, filter) becomes a parameter or branch of the existing service, or a new `<Verb><Noun>Service` that delegates to the existing shared step (S5). Its body is never copied into a new class. A shared step is a `*Service` registered in DI and injected (S1, S5); a new helper kind with no precedent on `main` (`*Rotator`, `*Policy`, a `*Loader` that is not an S4 helper) is not introduced. [#209 review: `RefreshTokenRotator`, `SignOutAdminService`]
- **H3** One `<Domain>Route.kt` and one `<Domain>Docs.kt` per domain. A second route file exists only for a separate resource subtree, as `LinkPreviewRoute` does; never for an audience or a role. A new endpoint on an existing resource goes into the existing route file, next to the resource's other handlers. [#209 review: `AiAdminRoute`]
- **H4** Only the type kinds F2 lists exist: domain, VO, enum, `*DomainService`, DTO, S4 helper, and the infrastructure kinds already on `main`. When no F2 line fits, write the question under Ambiguity in section 3 and let the user decide; do not solve it with a new kind of type. [#209 review: `AuthAdminPolicy`, `AiAdminPolicy`]

## Code

- **H5** No comments and no KDoc in `src/main`. The few that `main` carries (ErrorCode group labels, three auth client headers) are not precedent. Name the thing so the comment is unnecessary. [#170–#173 review]
- **H6** No `require`, `requireNotNull`, `check`, `checkNotNull` or `error()` anywhere in `src/main`: domains, services, clients, workers, config and startup checks included. Every intentional failure is a `BusinessException` with an `ErrorCode` (E0–E4), thrown through `requireTrue`, `requireFalse` or `?: throw`. When no code fits, add one; internal failures get a 500 code such as `AI_500_0001`. The only exception is a third-party builder method that happens to be named `.require(...)`. [#170 review, #176]
- **H7** A class that logs takes `private val log: Logger` as a constructor parameter, and the DI module creates it with `LoggerFactory.getLogger(X::class.java)`. `LoggerFactory` appears only under `di/` and inside plugin install functions, never as a property initializer in a class. [#168, #172 review, #178]

## Review

A domain review (section 3), the review before a PR and the PR review check these in addition to `domain-review.md`:

- Diff every added or renamed file against its closest sibling on `main` (`git show origin/main:<sibling path>` beside the new file) and report differences in shape, not only rule violations. A review that read only the written rules is incomplete.
- Grep the changed `src/main` files for `LoggerFactory` outside `di/` and plugins; for `require(`, `requireNotNull(`, `check(`, `checkNotNull(` and `error(` other than `requireTrue`/`requireFalse`; and for `//`, `/*` and `/**`. Every hit is a finding.
- A new service, a second route file or a type kind is justified by naming the `main` precedent it follows or the `domain-review.md` rule that requires it. "No rule forbids it" is not a justification.
