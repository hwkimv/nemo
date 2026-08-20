# AlbumPhoto·PhotoTag Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve album photo order through an explicit `AlbumPhoto` relation and provide authenticated photo-tag create/list/delete APIs with ownership, friendship, duplicate, position, and shared-album access rules.

**Architecture:** Replace `Album.photos` many-to-many state with an aggregate-owned `List<AlbumPhoto>` mapped onto the existing `album_photos` table. Add a separate `PhotoTag` aggregate service that authorizes against `Photo`, accepted friendship, and accepted album sharing before persisting or revealing tags. Use a PostgreSQL Flyway V1 migration in production while H2 test/dev profiles continue to let Hibernate create fresh schemas.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring Data JPA, Jakarta Validation, JUnit 5, AssertJ, MockMvc, H2, PostgreSQL, Flyway

**Spec:** `docs/superpowers/specs/2026-08-20-album-photo-photo-tag-domain-design.md`

## Global Constraints

- Work only in `/mnt/c/Users/hwkim/DEV/nemo-worktrees/album-photo-tag-domain` on `codex/album-photo-tag-domain`; do not modify the dirty `/mnt/c/Users/hwkim/DEV/nemo-app` checkout or the team repository.
- Preserve the existing `album_photos` table and rows; historical rows receive deterministic `photo_id ASC` sequence values, not a claim of recovered user order.
- Keep existing album request and response shapes unchanged, and keep the 100-album list query count at exactly 4.
- Photo tag positions are normalized doubles in the inclusive range `0.0..1.0`; one user may be tagged at most once per photo.
- Tag creation is owner-only and accepted-friend-only; listing requires owner or accepted shared-album access; deletion requires photo owner or the tagged user.
- Validate photo ownership/access before target-user and duplicate checks to avoid exposing photo existence.
- Do not add Flutter UI, tag-edit API, arbitrary reorder API, notifications, or production/Supabase deployment.
- Follow RED → verify RED → GREEN → verify GREEN for every production behavior.
- Do not commit, push, or open a PR without separate user authorization.

---

## File Structure

- `domain/album/entity/AlbumPhotoId.java`: composite identity for the existing join table.
- `domain/album/entity/AlbumPhoto.java`: relation entity and stored sequence.
- `domain/album/entity/Album.java`: aggregate methods for ordered membership and sequence compaction.
- `domain/album/repository/AlbumPhotoRepository.java`: cross-domain membership/access queries without loading every album collection.
- `domain/phototag/*`: photo-tag entity, repository, DTOs, service, and controller.
- `resources/db/migration/V1__album_photo_and_photo_tag.sql`: existing-row-safe PostgreSQL schema transition.
- Existing album/photo/auth services: replace direct `getPhotos()` mutation/traversal with the new aggregate or repository boundary.

### Task 1: AlbumPhoto Aggregate Model

**Files:**
- Create: `backend/src/test/java/com/nemo/backend/domain/album/entity/AlbumPhotoModelTest.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/album/entity/AlbumPhotoId.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/album/entity/AlbumPhoto.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/album/entity/Album.java`

**Interfaces:**
- Produces: `Album.addPhoto(Photo,int)`, `Album.containsPhoto(Long)`, `Album.removePhotos(Set<Long>)`, `Album.compactSequences()`, `Album.orderedAlivePhotos()`, `Album.clearPhotos()`, `Album.getAlbumPhotos()`.
- Produces: `AlbumPhoto.getPhoto()`, `AlbumPhoto.getSequence()`, `AlbumPhoto.updateSequence(int)`.

- [ ] **Step 1: Write the failing aggregate tests**

  Cover request-order preservation, duplicate rejection, append position, deletion compaction, and exclusion of soft-deleted photos. Hand-derive expected photo IDs and sequence values; do not compute them through production helpers.

- [ ] **Step 2: Run the focused test and verify RED**

  Run: `./gradlew test --tests '*AlbumPhotoModelTest' --no-daemon`

  Expected: compilation fails because `AlbumPhoto` and the new `Album` methods do not exist.

- [ ] **Step 3: Implement the minimal aggregate model**

  Map `AlbumPhotoId(albumId, photoId)` with `@Embeddable`; map both relations with `@MapsId`, map `sequence` as non-null, and map `Album.albumPhotos` with cascade/all and orphan removal. `addPhoto` must no-op on an existing photo ID; `removePhotos` must compact after removal; `orderedAlivePhotos` must sort by stored sequence and filter `deleted == true`.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Run: `./gradlew test --tests '*AlbumPhotoModelTest' --no-daemon`

  Expected: PASS.

### Task 2: Album Persistence and API Compatibility

**Files:**
- Create: `backend/src/main/java/com/nemo/backend/domain/album/repository/AlbumPhotoRepository.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/album/repository/AlbumRepository.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/album/service/AlbumService.java`
- Modify: `backend/src/test/java/com/nemo/backend/domain/album/service/AlbumPhotoOwnershipTest.java`
- Modify: `backend/src/test/java/com/nemo/backend/domain/album/service/AlbumListQueryCountTest.java`
- Modify: `backend/src/test/java/com/nemo/backend/domain/album/service/AlbumPaginationTest.java`
- Create: `backend/src/test/java/com/nemo/backend/domain/album/service/AlbumPhotoSequenceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 aggregate methods.
- Produces: unchanged album HTTP/service contracts with persisted sequence ordering.
- Produces: `AlbumPhotoRepository.existsAccessiblePhoto(Long photoId, Long userId, AlbumShare.Status status)` and membership/count query methods used by other domains.

- [ ] **Step 1: Write failing service/integration tests**

  Assert that create preserves `[photoB, photoA]`, add appends in request order without duplicates, remove compacts sequences to `[0..n-1]`, detail/download return stored order, and the existing 100-album fixture still executes 4 SQL statements.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew test --tests '*AlbumPhotoSequenceIntegrationTest' --tests '*AlbumListQueryCountTest' --no-daemon`

  Expected: FAIL because services and JPQL still use `Album.photos`.

- [ ] **Step 3: Refactor album persistence paths**

  Change create/add/remove/cover/delete/thumbnail/detail/download logic to the Task 1 methods. Preserve input order by indexing the requested IDs and sorting repository results before `addPhoto`. Return stored `AlbumPhoto.sequence` in download DTOs. Change `findAlivePhotoRows` to `JOIN a.albumPhotos ap JOIN ap.photo p` and order rows by `ap.sequence` where the consumer relies on order.

- [ ] **Step 4: Update fixtures to build relations through `addPhoto`**

  Replace `setPhotos(...)` in existing tests with explicit `addPhoto(photo, index)` calls; keep behavioral and query-count expectations unchanged.

- [ ] **Step 5: Run album tests and verify GREEN**

  Run: `./gradlew test --tests 'com.nemo.backend.domain.album.*' --no-daemon`

  Expected: PASS, including query count 4.

### Task 3: Cross-Domain Album Membership Consumers

**Files:**
- Modify: `backend/src/main/java/com/nemo/backend/domain/album/service/AlbumShareService.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/photo/service/PhotoServiceImpl.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/photo/controller/PhotoController.java`
- Modify: `backend/src/main/java/com/nemo/backend/domain/auth/service/AuthService.java`
- Create: `backend/src/test/java/com/nemo/backend/domain/photo/service/SharedAlbumPhotoAccessTest.java`

**Interfaces:**
- Consumes: `AlbumPhotoRepository` membership/access queries and Task 1 aggregate cleanup.
- Produces: existing shared-photo download access and account-deletion behavior without `Album.getPhotos()`.

- [ ] **Step 1: Write failing access regression tests**

  Prove an accepted shared-album member can access a linked alive photo, a pending/inactive share cannot, and an unrelated user cannot. Assert outcomes from the real service, not repository-mock invocation counts.

- [ ] **Step 2: Run the focused test and verify RED**

  Run: `./gradlew test --tests '*SharedAlbumPhotoAccessTest' --no-daemon`

  Expected: FAIL until membership traversal is replaced.

- [ ] **Step 3: Replace every remaining direct photo collection traversal**

  Use aggregate counts for already-loaded albums and repository existence/ID queries for authorization paths. Replace account/album cleanup with `clearPhotos()` or explicit relation deletion. Confirm `rg 'getPhotos\(|setPhotos\(' backend/src/main backend/src/test` returns no old album collection access.

- [ ] **Step 4: Run photo/auth/album tests and verify GREEN**

  Run: `./gradlew test --tests 'com.nemo.backend.domain.photo.*' --tests 'com.nemo.backend.domain.auth.*' --tests 'com.nemo.backend.domain.album.*' --no-daemon`

  Expected: PASS.

### Task 4: PhotoTag Domain and Service Rules

**Files:**
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/entity/PhotoTag.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/repository/PhotoTagRepository.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/dto/CreatePhotoTagRequest.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/dto/PhotoTagResponse.java`
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/service/PhotoTagService.java`
- Modify: `backend/src/main/java/com/nemo/backend/global/exception/ErrorCode.java`
- Create: `backend/src/test/java/com/nemo/backend/domain/phototag/service/PhotoTagServiceTest.java`

**Interfaces:**
- Produces: `PhotoTagService.create(Long requesterId, Long photoId, CreatePhotoTagRequest)`.
- Produces: `PhotoTagService.list(Long requesterId, Long photoId)`.
- Produces: `PhotoTagService.delete(Long requesterId, Long photoId, Long tagId)`.
- Produces: error codes `PHOTO_TAG_ALREADY_EXISTS`, `PHOTO_TAG_NOT_FOUND`, `PHOTO_TAG_FORBIDDEN`, `PHOTO_TAG_SELF_NOT_ALLOWED`, `PHOTO_TAG_TARGET_NOT_FRIEND`, `PHOTO_TAG_POSITION_INVALID`.

- [ ] **Step 1: Write failing service tests for the authorization matrix**

  Cover owner + accepted friend success; non-owner, deleted photo, self, non-friend, out-of-range coordinate, and duplicate rejection; owner/shared-member listing; owner/tagged-user deletion; unrelated-user deletion rejection. Verify returned DTO fields and persisted state rather than mock calls.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew test --tests '*PhotoTagServiceTest' --no-daemon`

  Expected: compilation fails because the photo-tag domain does not exist.

- [ ] **Step 3: Implement entities, repository queries, error codes, and service**

  Store `Photo`, tagged `User`, normalized coordinates, and `createdAt`; enforce the DB uniqueness contract in the mapping. In `create`, authorize the photo before loading the target or checking duplicates. Accept either direction of an `ACCEPTED` friendship. In `list`, accept the owner or an active accepted share linked through `AlbumPhotoRepository`. In `delete`, verify `{photoId,tagId}` association before allowing owner or the tagged user.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run: `./gradlew test --tests '*PhotoTagServiceTest' --no-daemon`

  Expected: PASS.

### Task 5: PhotoTag HTTP API

**Files:**
- Create: `backend/src/main/java/com/nemo/backend/domain/phototag/controller/PhotoTagController.java`
- Create: `backend/src/test/java/com/nemo/backend/domain/phototag/controller/PhotoTagControllerTest.java`

**Interfaces:**
- Consumes: Task 4 service.
- Produces: `POST /api/photos/{photoId}/tags` → 201, `GET /api/photos/{photoId}/tags` → 200, `DELETE /api/photos/{photoId}/tags/{tagId}` → 204.

- [ ] **Step 1: Write failing MockMvc contract tests**

  Authenticate with the project JWT/security test pattern. Assert exact status codes, request validation for null IDs/coordinates, response field names (`tagId`, `taggedUserId`, `nickname`, `positionX`, `positionY`, `createdAt`), and error-code serialization for one representative failure.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew test --tests '*PhotoTagControllerTest' --no-daemon`

  Expected: FAIL with unmapped routes or missing controller.

- [ ] **Step 3: Implement the controller**

  Extract requester ID with the existing `AuthExtractor`, annotate the create body with `@Valid`, return `ResponseEntity.status(CREATED)`, `ok`, and `noContent` respectively, and delegate business rules to `PhotoTagService`.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run: `./gradlew test --tests '*PhotoTagControllerTest' --tests '*PhotoTagServiceTest' --no-daemon`

  Expected: PASS.

### Task 6: PostgreSQL Migration and Profile Configuration

**Files:**
- Modify: `backend/build.gradle`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-prod.yml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Create: `backend/src/main/resources/db/migration/V1__album_photo_and_photo_tag.sql`
- Create: `backend/src/test/java/com/nemo/backend/migration/AlbumPhotoTagMigrationTest.java`

**Interfaces:**
- Produces: Flyway baseline version `0` and production migration V1.
- Consumes: Task 1 and Task 4 table mappings.

- [ ] **Step 1: Write a failing PostgreSQL migration smoke test**

  Against a disposable PostgreSQL schema, create the pre-change `album_photos(album_id,photo_id)` table with deliberately unordered rows, run Flyway V1, and assert row preservation, per-album `photo_id ASC` sequence `[0..n-1]`, uniqueness constraints, and the `photo_tag` table. Also assert duplicate input rows make migration fail without deleting data.

- [ ] **Step 2: Run the migration test and verify RED**

  Run: `./gradlew test --tests '*AlbumPhotoTagMigrationTest' --no-daemon`

  Expected: FAIL because Flyway and V1 are absent; if no disposable PostgreSQL is available, record `[측정 필요]` and do not claim the smoke test passed.

- [ ] **Step 3: Add Flyway and the migration**

  Add `flyway-core` and `flyway-database-postgresql`. Configure production with `enabled: true`, `baseline-on-migrate: true`, `baseline-version: 0`; disable Flyway in default/dev/test H2 flows. V1 must raise on duplicate `(album_id,photo_id)`, add/backfill/set-not-null `sequence`, add the composite membership key/unique order constraint, and create indexed/unique `photo_tag` foreign keys without silently changing existing memberships.

- [ ] **Step 4: Run migration and context tests and verify GREEN**

  Run: `./gradlew test --tests '*AlbumPhotoTagMigrationTest' --tests '*AlbumPhotoSequenceIntegrationTest' --no-daemon`

  Expected: PASS when PostgreSQL is available; otherwise the migration test must be explicitly skipped by environment assumption and reported as unverified, never silently green.

### Task 7: Evidence Documentation and Final Verification

**Files:**
- Modify: `README.md`
- Create: `docs/album-photo-photo-tag-implementation.md`

**Interfaces:**
- Consumes: verified behavior and measurements from Tasks 1–6.
- Produces: code-grounded documentation that distinguishes implementation, test evidence, historical-order limitation, and deployment status.

- [ ] **Step 1: Update documentation from actual evidence only**

  Describe relation purpose, API routes, authorization rules, migration behavior, the exact tests run, and the fact that local implementation is not Supabase deployment. Mark unavailable PostgreSQL evidence `[측정 필요]`.

- [ ] **Step 2: Run the complete backend suite**

  Run: `./gradlew test --no-daemon`

  Expected: `BUILD SUCCESSFUL` with no failed tests.

- [ ] **Step 3: Re-run critical regression and static checks**

  Run: `./gradlew test --tests '*AlbumListQueryCountTest' --no-daemon`

  Expected: PASS with the asserted query count of 4.

  Run: `rg 'getPhotos\(|setPhotos\(' backend/src/main backend/src/test`

  Expected: no obsolete `Album.photos` consumers.

  Run: `git diff --check`

  Expected: no whitespace errors.

- [ ] **Step 4: Review exact scope and evidence boundary**

  Run: `git status --short && git diff --stat && git diff -- backend`

  Confirm the dirty original checkout and team repository were not changed, no secret/config value was introduced, no production deployment was performed, and no commit/push occurred.
