# 배포

EC2 1대에 컨테이너 하나를 띄우는 구조입니다. `nemo-deploy.sh` 가
**배포 → readiness 확인 → 실패 시 이전 이미지로 rollback** 까지 합니다.

## 왜 GitHub Actions 가 EC2 에 직접 접속하지 않는가

push 방식 배포를 붙이려면 둘 중 하나가 필요한데, 둘 다 이 환경에서 문제가 있습니다.

| 방식 | 필요한 것 | 이 환경에서의 문제 |
|---|---|---|
| SSH | GitHub Secrets 에 SSH 개인키, 22번 포트 개방 | **Elastic IP 가 없어 켤 때마다 퍼블릭 IP 가 바뀝니다.** Secrets 에 호스트를 박아두면 재기동마다 깨집니다. 그리고 현재 22번은 개발자 IP `/32` 만 열려 있는데, GitHub 러너 대역까지 열면 경계가 크게 넓어집니다 |
| OIDC → SSM | GitHub OIDC provider, 제한된 deploy role, 인스턴스의 SSM 등록 | **현재 IAM 권한으로 구축 불가.** 아래 실측 참고 |

OIDC → SSM 쪽이 인바운드 포트도 장기 자격증명도 필요 없어 더 낫습니다.
그래서 먼저 확인했는데, 셋 다 막혀 있었습니다.

```
$ aws iam list-open-id-connect-providers
{ "OpenIDConnectProviderList": [] }              # provider 없음

$ aws iam list-attached-user-policies --user-name admin_test
... IAMReadOnlyAccess                            # provider·role 생성 불가

$ aws iam list-attached-role-policies --role-name nemo-ec2-role
... nemo-s3-access 하나뿐                         # SSM managed instance 가 아님
```

**없는 권한을 억지로 만들지 않았습니다.** 대신 배포 실행을 호스트로 옮기고,
사람이 하던 확인(readiness)과 복구(rollback)를 스크립트가 하게 했습니다.

이건 **deployment automation** 이지 continuous deployment 가 아닙니다.
`main` push 마다 자동으로 나가지 않습니다 — 인스턴스가 평소 정지돼 있어
그렇게 하는 것이 맞지도 않습니다.

## 설치 (호스트에서 1회)

```bash
curl -fsSL https://raw.githubusercontent.com/hwkimv/nemo/main/infra/deploy/nemo-deploy.sh \
  -o ~/nemo-deploy.sh && chmod +x ~/nemo-deploy.sh
```

`/home/ec2-user/nemo.env` (권한 600) 가 있어야 합니다. 이 파일에만 런타임 비밀값이 있고,
**이미지에도 GitHub Secrets 에도 들어가지 않습니다.**

## 배포

```bash
~/nemo-deploy.sh <image-tag>
```

`<image-tag>` 는 Deploy 워크플로가 요약에 출력하는 커밋 SHA 입니다.
스크립트에 넘기는 것은 이 태그 하나뿐입니다 — 비밀값은 받지 않습니다.

| 종료 코드 | 뜻 |
|---|---|
| 0 | 배포 성공 (readiness 확인됨) |
| 1 | 새 이미지 실패 → 이전 이미지로 rollback 성공. **서비스는 살아 있습니다** |
| 2 | rollback 까지 실패 — 사람이 봐야 합니다 |
| 3 | 사용법 오류 / 사전 조건 불충족 |

되돌리기만 하려면:

```bash
~/nemo-deploy.sh --rollback
```

## 어떻게 동작하는가

```
1. docker pull <새 이미지>
     실패하면 기존 컨테이너를 건드리지 않고 종료 (서비스 유지)
2. 기존 컨테이너 제거 → 새 이미지로 기동      ← 여기서 중단이 있다
3. /readyz 를 2초 간격으로 폴링 (상한 120초)
     컨테이너가 조기 종료되면 기다리지 않고 즉시 실패로 본다
4a. UP  → last-good 갱신, exit 0
4b. 실패 → 컨테이너 로그를 ~/deploy-logs/ 에 저장
          → 이전 이미지로 다시 기동 → readiness 재확인
          → 회복되면 exit 1, 안 되면 exit 2
```

`last-good` 은 `~/.nemo-last-good` 에 있고 **readiness 까지 확인된 이미지만** 기록됩니다.
실패한 이미지가 롤백 후보가 되는 일은 없습니다.

## 알려진 한계

- **zero-downtime 이 아닙니다.** 포트 8080 을 직접 쓰는 단일 인스턴스라
  2번 단계에서 중단이 있습니다. 앞단에 Nginx/ALB 를 두면 없앨 수 있지만
  지금 필요하지 않은 인프라라 넣지 않았습니다.
- **DB 마이그레이션은 롤백되지 않습니다.** Flyway 가 적용한 스키마 변경은
  이전 이미지로 되돌려도 그대로 남습니다. 하위 호환되지 않는 마이그레이션을
  포함한 배포는 이 스크립트로 안전하게 되돌릴 수 없습니다.
- **알림이 없습니다.** 실패해도 종료 코드로만 알 수 있습니다.
- **Docker HEALTHCHECK 가 컨테이너를 재시작시키지 않습니다.**
  `--restart unless-stopped` 는 프로세스 종료에만 반응합니다.
  HEALTHCHECK 는 `docker ps` 관측과 배포 판정에만 씁니다.
