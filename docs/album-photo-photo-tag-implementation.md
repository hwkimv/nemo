# AlbumPhoto·PhotoTag 구현 근거

## 왜 바꿨는가

기존 `Album`과 `Photo`는 `@ManyToMany`였다. 사진이 앨범에 속한다는 사실만 저장할 수 있고, 사용자가 고른 순서는 저장할 칸이 없었다. 문서에 있던 친구 위치 태그도 엔티티와 API가 없었다.

이번 개인 고도화에서는 빈 도메인 이름만 추가하지 않고 다음 흐름까지 구현했다.

- 앨범 생성 요청의 사진 순서를 DB `sequence`로 저장
- 사진 추가 시 마지막 순서 뒤에 붙이고, 삭제 시 0부터 다시 압축
- 앨범 상세와 다운로드 목록에서 저장된 순서를 사용
- 사진 소유자가 수락된 친구를 좌표에 태그
- 사진 소유자와 수락된 공유 앨범 멤버가 태그 조회
- 사진 소유자 또는 태그된 사용자가 태그 삭제

## 데이터 모델

`AlbumPhoto`는 기존 `album_photos` 테이블을 그대로 사용한다. `(album_id, photo_id)` 복합키와 앨범별 `sequence`를 가진다. 별도 대리키나 새 연결 테이블을 만들지 않아 기존 멤버십 행을 유지한다.

`PhotoTag`는 사진, 태그된 사용자, 정규화 좌표 `positionX/positionY`, 생성 시각을 저장한다. 좌표는 각각 `0.0..1.0`이며 `(photo_id, tagged_user_id)`는 유일하다.

## API와 권한

| API | 성공 | 권한·검증 |
|---|---:|---|
| `POST /api/photos/{photoId}/tags` | 201 | 사진 소유자, 수락 친구, 본인 제외, 좌표 범위, 중복 방지 |
| `GET /api/photos/{photoId}/tags` | 200 | 사진 소유자 또는 활성·수락된 공유 앨범 멤버 |
| `DELETE /api/photos/{photoId}/tags/{tagId}` | 204 | 사진 소유자 또는 해당 태그의 사용자 |

생성에서는 사진 소유권을 태그 대상 조회와 중복 확인보다 먼저 검사한다. 남의 사진 ID로 친구나 태그 존재 여부를 탐색하지 못하게 하기 위해서다.

## 마이그레이션

운영 프로필만 Flyway를 활성화한다. 기존 스키마는 version `0`으로 baseline하고 V1이 다음을 수행한다.

1. `album_photos.sequence` 추가
2. 중복 `(album_id, photo_id)`가 있으면 마이그레이션 중단
3. 앨범별 `photo_id ASC`로 0부터 sequence 백필
4. 복합키와 앨범별 sequence 유일 제약 추가
5. `photo_tag` 테이블과 FK·유일·좌표 제약 생성

과거 테이블에는 사용자 순서가 없었다. 백필 순서는 재현 가능한 초기값일 뿐, 과거 사용자 선택 순서를 복원했다는 뜻이 아니다.

## 검증 근거

- `AlbumPhotoModelTest`: 중복 방지, 저장 순서, 삭제 뒤 압축, soft-delete 제외
- `AlbumPhotoSequenceIntegrationTest`: 생성·추가·삭제·상세·다운로드 순서
- `AlbumListQueryCountTest`: 앨범 100개 목록 SQL 4개 유지
- `PhotoTagServiceTest`: 생성·조회·삭제 권한과 친구·중복·좌표 검증
- `PhotoTagControllerTest`: 201/200/204와 요청 검증·오류 응답 계약
- `PhotoTagIntegrationTest`: 실제 H2 영속화와 공유 앨범 접근·태그 당사자 삭제
- `AlbumPhotoTagMigrationTest`: 기존 행 보존, 결정적 sequence 백필, 중복 시 중단

## 증거 경계

- H2 PostgreSQL 호환 모드에서 Flyway 이전 로직을 검증했다.
- 현재 WSL에는 Docker와 `psql`이 없어 실제 PostgreSQL 실행은 `[측정 필요]`다.
- Supabase 운영 스키마 적용과 배포는 하지 않았다. 현재 상태는 개인 저장소의 로컬 구현이다.
- Flutter 태그 UI와 알림은 이번 범위에 포함하지 않았다.
