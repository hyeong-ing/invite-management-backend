# 🔒 Invite and Management 🔑

<br/>

<p align="center">

  <br/>
  이전 프로젝트에서 회원가입과 로그인을 구현했지만, 로그아웃이나 인증 상태 유지, 권한 처리까지 깊게 다루지 못했습니다. <br/>
  그리고 DB도 H2 위주로만 사용해서 실제 DB를 설계하고 관리하는 경험이 부족하다고 느꼈습니다.<br/>
  그래서 이번 프로젝트에서는 Keycloak을 활용한 인증, Spring Security 기반의 권한 처리,<br/>
  MySQL을 사용한 고객·관리자 데이터 관리까지 경험하기로 했습니다. <br/>
  <br/>

  <br/>

  <img width="800" height="450" alt="image" src="https://github.com/user-attachments/assets/25ed9b61-e432-438e-a6c4-a49c78ee17c9" />

</p>

<br/>
<br/>
<br/>

### 🔶 프로젝트 관련 링크

+ [Blog (프로젝트 기록)](https://post-this.tistory.com/category/%F0%9F%92%BB%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8/%F0%9F%90%A0%ED%9A%8C%EC%9B%90%EA%B0%80%EC%9E%85%20%ED%8E%98%EC%9D%B4%EC%A7%80%F0%9F%90%A0)
+ [YouTube (동작화면)](https://youtu.be/g4nXTk9zHbc)
+ [Figma (다이어그램)](https://www.figma.com/board/ymjJijKbxc8MZPF4UT8Ysn/Invite-and-Management?node-id=0-1&t=RE8pZSuCVea9F2X6-1)


<br/>
<br/>


### 🔶 프로젝트 설명

<br/>

<p align="center">

  <img width="1000" height="500" alt="스크린샷 2026-07-03 오후 8 56 27" src="https://github.com/user-attachments/assets/1c970f5a-b9bf-4415-97db-2189df8f5795" />

</p>

<br/>

+ 사용자는 초대코드를 입력해 고객 등급별 안내 페이지로 이동합니다.
+ 관리자는 Keycloak 로그인을 통해 인증을 진행하고 JWT를 포함해 백엔드 API를 호출합니다.
+ 초대코드 확인은 로그인 없이 사용할 수 있습니다. 고객 관리와 관리자 권한 관리 API는 Spring Security로 JWT와 권한을 검증합니다.
+ 고객·관리자·권한 데이터는 MySQL에 저장하고 고객 검색 데이터는 Elasticsearch에 저장해 검색 기능에 활용합니다.
+ MySQL과 Elasticsearch·Keycloak 사이의 데이터 변경은 Outbox를 통해 별도로 반영하고 실패한 작업은 재시도하도록 구성했습니다.


<br/><br/> 

### 🔶 기술 스택 & 라이브러리
+ 백엔드 : Java 17, Spring Boot 3.5.6
+ 데이터베이스 : MySQL, Spring Data JPA
+ 검색 엔진 : Elasticsearch, Spring Data Elasticsearch
+ 보안 : Spring Security, OAuth2 Resource Server, JWT, Keycloak
+ 관리자 계정 연동 : Keycloak Admin Client
+ 데이터 동기화 : Transactional Outbox, Spring Scheduling
+ 실행 환경 : Docker
  
<br/><br/>

### 🔶 프로젝트 목표
+ DB에 저장된 초대코드를 조회하고 해당 고객 등급별 안내 페이지로 이동하는 흐름 구현하기.
+ MySQL과 JPA로 고객, 관리자, 권한 데이터를 관리하는 실제 DB 기반 프로젝트 경험하기.
+ Elasticsearch를 활용한 고객 검색 기능 구현하기.
+ Keycloak과 Spring Security를 활용한 로그인/로그아웃 및 권한 기반 접근 제어 경험하기.
+ 서로 다른 시스템의 데이터 변경 과정에서 발생할 수 있는 불일치를 이해하고 복구 구조 구현하기.
+ Docker를 사용해 Keycloak, Elasticsearch 같은 외부 시스템을 실행하고 백엔드와 연동하는 흐름 이해하기.

<br/><br/>

### 🔶 핵심 로직
1) 초대코드 검증 및 등급별 페이지 이동 <br/>
사용자가 4자리 초대코드를 입력하면 백엔드에서 DB에 저장된 초대코드와 일치하는 고객을 조회합니다.

+ 초대코드는 InviteCode 값 객체를 통해 숫자 4자리인지 검증합니다.
+ 유효한 코드라면 고객 정보를 반환합니다.
+ 그리고 프론트엔드에서 고객 등급에 따라 VIP, VVIP, DIAMOND 페이지로 이동합니다.

```java
public Optional<CustomerResponse> findByCode(String code) {
    if (!InviteCode.isValid(code)) {
        return Optional.empty();
    }

    return customerRepository.findByInviteCode(InviteCode.of(code))
            .map(customerService::toResponse);
}
```
```javascript
const grade = (data.grade || "").trim().toUpperCase();

if (grade === "VIP") navigate("/vip");
else if (grade === "VVIP") navigate("/vvip");
else if (grade === "DIAMOND") navigate("/diamond");
```

<br/><br/>

----

2) 고객 등록∙수정 시 초대코드 중복 방지 <br/>
고객을 등록하거나 수정할 때 동일한 초대코드가 저장되지 않도록 검증했습니다.

+ 등록 시에는 같은 초대코드가 이미 존재하는지 확인합니다.
+ 수정 시에는 자기 자신의 기존 코드만 허용하고 다른 고객이 사용하는 코드만 중복으로 판단합니다.
+ DB에서는 `code` 컬럼에 unique 제약 조건을 두어 한 번 더 중복을 방지했습니다.

```java
if (customerRepository.existsByInviteCode(inviteCode)) {
    throw new DuplicateCodeException("초대코드가 중복되었습니다.");
}
```
```java
customerRepository.findByInviteCode(newInviteCode).ifPresent(found -> {
    if (!found.getId().equals(id)) {
        throw new DuplicateCodeException("초대코드가 중복되었습니다.");
    }
});
```

<br/><br/>

----

3) Outbox 기반 Elasticsearch 검색 데이터 동기화 <br/>
고객 원본 데이터는 MySQL에 저장하고, 검색에 사용할 데이터는 Elasticsearch에 별도로 반영했습니다.

+ 고객 등록·수정·삭제 시 MySQL에 고객 변경과 Outbox 이벤트를 같은 트랜잭션으로 저장합니다.
+ Spring Scheduler가 대기 중인 이벤트를 읽어 Elasticsearch에 반영합니다.
+ 반영에 실패하면 일정 시간 후 재시도하고 재시도 한도를 초과하면 FAILED 상태로 관리합니다.
+ 별도의 정합성 점검 작업을 통해 MySQL과 검색 인덱스의 불일치도 확인하도록 했습니다.
+ 별도의 메시지 브로커나 CDC 없이 MySQL과 Spring Scheduling으로 동기화 구조를 구현했습니다.

```java
Customer saved = customerRepository.save(customer);
searchSync.record(saved);
```

```java
customerRepository.delete(customer);
searchSync.recordDeletion(id);
```

Outbox에 기록된 작업은 별도 Scheduler가 처리합니다.

```java
@Scheduled(
    fixedDelayString = "${app.search-sync.poll-delay-ms:2000}",
    initialDelayString = "2000"
)
public void deliver() {
    try {
        sync.processPending();
    } catch (Exception e) {
        log.error("Search outbox processing failed; committed work will be retried", e);
    }
}
```

<br/><br/>

----

4) Elasticsearch 기반 고객 검색 및 초성 검색 <br/>
고객명, 등급, 연락처, 초대코드, 메모를 검색할 수 있도록 Elasticsearch를 사용했습니다.

+ name, note는 한글 검색을 고려해 analyzer를 적용했습니다.
+ 고객 이름에서 초성을 추출해 nameChosung 필드에 저장했습니다.
+ 예를 들어 홍길동은 ㅎㄱㄷ으로 저장되어 초성 검색이 가능합니다.
+ 삭제된 고객은 검색 결과에 포함되지 않도록 처리했습니다.

```java
public CustomerSearchEntity toSearchEntity(Customer customer) {
    CustomerSearchEntity entity = new CustomerSearchEntity();

    entity.setId(customer.getId());
    entity.setName(customer.getName());
    entity.setGrade(customer.getGrade());
    entity.setPhone(customer.getPhone());
    entity.setCode(customer.getCode());
    entity.setNote(customer.getNote());
    entity.setNameChosung(getChosung(customer.getName()));

    return entity;
}
```
```java
if (c >= '가' && c <= '힣') {
    int uniVal = c - 0xAC00;
    int choIdx = uniVal / 588;
    sb.append(cho[choIdx]);
}
```
```java
@Query("""
{
  "bool": {
    "should": [
      { "match": { "name": { "query": "?0" } } },
      { "match": { "note": { "query": "?0" } } },
      { "wildcard": { "grade": { "value": "*?0*" } } },
      { "wildcard": { "phone": { "value": "*?0*" } } },
      { "wildcard": { "code": { "value": "*?0*" } } },
      { "wildcard": { "nameChosung": { "value": "*?0*" } } }
    ],
    "minimum_should_match": 1
  }
}
""")
List<CustomerSearchEntity> searchAll(String keyword);
```

<br/><br/>

----

5) JWT 인증 정보와 DB 권한을 결합한 권한 제어 <br/>
Keycloak에서 발급받은 JWT를 Spring Security Resource Server가 검증하고 <br/>
DB에 저장된 관리자 권한을 함께 읽어 API 접근 권한을 제어했습니다.

+ JWT의 sub 값으로 MySQL에 연결된 관리자를 조회합니다.
+ 관리자 상태가 ACTIVE가 아니면 인증을 거부합니다.
+ DB에 저장된 관리자 역할과 권한을 Spring Security의 Authority로 변환합니다.
+ Controller에서는 @PreAuthorize를 사용해 API별 접근 권한을 분리했습니다.
+ 이를 통해 Keycloak은 로그인 인증을 담당하고 서비스의 역할 및 세부 권한은 MySQL을 기준으로 관리하도록 분리했습니다.

```java
String keycloakId = jwt.getSubject();

Admin admin = keycloakId == null ? null
        : adminRepository.findByKeycloakId(keycloakId).orElse(null);
```
```java
if (admin.getRole() != null) {
    authorities.add(new SimpleGrantedAuthority(
            "ROLE_" + admin.getRole().name()
    ));
}
```
<br/><br/>

----

6) Outbox 기반 Keycloak 관리자 계정 동기화

관리자 정보는 MySQL에 저장하고 실제 로그인 계정은 Keycloak에 생성하도록 구성했습니다.

+ 관리자 등록 시 MySQL에 관리자 정보와 Outbox 작업을 함께 저장합니다.
+ 생성 대기 상태인 PENDING_CREATE에서 Keycloak 계정 생성이 완료되면 ACTIVE로 변경합니다.
+ 관리자 수정·삭제도 Outbox에 기록하고 별도 작업으로 반영합니다.
+ 실패한 작업은 재시도하며, 계정 상태에 따라 서비스 접근 여부를 제어합니다.

```java
admin.setState(Admin.State.PENDING_CREATE);

Admin savedAdmin = adminRepository.save(admin);
accountSync.enqueue(savedAdmin);
```

Keycloak 연동은 별도 작업에서 수행합니다.

```java
case PENDING_CREATE -> {
    admin.setKeycloakId(gateway.ensureCreated(admin));
    gateway.updateProfile(admin);
    admin.setState(Admin.State.ACTIVE);
}
```

관리자 삭제 시에는 먼저 DELETING 상태로 변경하고 Keycloak 계정 삭제를 요청합니다.

```java
admin.setState(Admin.State.DELETING);
accountSync.enqueue(admin);
```
Keycloak 계정 삭제가 완료되기 전에도 DB의 관리자 상태를 통해 서비스 접근을 차단할 수 있도록 했습니다.


<br/><br/><br/>


### 🔶 문제 해결

### [ 관리자 권한 다대다 관계 설계와 중복 저장 방지 ] <br/>

1) 문제 발생 <br/>
+ 한 명의 관리자가 여러 권한을 가질 수 있고 하나의 권한도 여러 관리자에게 부여될 수 있는 구조가 필요했습니다.
+ 관계형 DB에서는 다대다 관계를 직접 관리하기 어렵고 권한 추가, 삭제 흐름도 명확하게 보이지 않았습니다.
+ 또한 같은 관리자에게 같은 권한이 중복 저장될 수 있는 문제가 있었습니다.

<br/><br/>

2) 원인 파악 <br/>
+ Admin과 Permission은 다대다 관계이지만, @ManyToMany를 직접 사용하면 중간 테이블을 객체로 다루기 어렵습니다.
+ 권한 연결 자체를 명확히 관리하고, 추후 필드 확장 가능성도 고려해야 했습니다.

<br/><br/>

3) 문제 해결 <br/>
+ AdminPermission 중간 엔티티를 만들어 관리자와 권한의 연결을 직접 관리했습니다.
+ admin_id, permission_id 조합에 unique 제약을 두어 중복 저장을 방지했습니다.
+ orphanRemoval = true를 사용해 권한 목록에서 제거된 연결 데이터가 DB에서도 삭제되도록 처리했습니다.
  
```java
@Table(
    name = "admin_permission",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_admin_permission",
        columnNames = {"admin_id", "permission_id"}
    )
)
public class AdminPermission {
    // ...
}
```
```java
@OneToMany(
    mappedBy = "admin",
    cascade = CascadeType.ALL,
    orphanRemoval = true
)
private Set<AdminPermission> adminPermissions = new LinkedHashSet<>();
```
```java
admin.getAdminPermissions().removeIf(adminPermission -> {
    Permission permission = adminPermission.getPermission();

    return permission == null
            || permission.getCode() == null
            || !requestedCodes.contains(permission.getCode());
});
```
```java
if (existingCodes.contains(permission.getCode())) {
    continue;
}

AdminPermission adminPermission = new AdminPermission();
adminPermission.setAdmin(admin);
adminPermission.setPermission(permission);
admin.getAdminPermissions().add(adminPermission);
```

<br/><br/>

### [ MySQL과 Elasticsearch 검색 인덱스 불일치 문제 ] <br/>

1) 문제 발생 <br/>
+ 고객 원본 데이터는 MySQL에 저장하고, 검색 데이터는 Elasticsearch에 저장했습니다.
+ 기존에는 고객 등록·수정·삭제 시 Elasticsearch도 바로 갱신하도록 구현했습니다.
+ 불일치가 발생하면 수동 동기화 API를 통해 검색 데이터를 다시 맞추는 방식이었습니다.

<br/><br/>

2) 원인 파악 <br/>
+ MySQL과 Elasticsearch는 서로 다른 저장소이기 때문에 하나의 DB 트랜잭션으로 처리할 수 없습니다.
+ MySQL 변경이 성공한 뒤 Elasticsearch 반영에 실패하면 두 저장소의 데이터가 달라질 수 있습니다.
+ 수동 동기화만으로는 실패한 변경 작업을 자동으로 추적하거나 복구하기 어려웠습니다.

<br/><br/>

3) 문제 해결 <br/>
+ Transactional Outbox 패턴을 적용해 고객 데이터와 검색 변경 이벤트를 MySQL 트랜잭션에 함께 저장했습니다.
+ Scheduler가 Outbox의 PENDING 작업을 읽어 Elasticsearch에 반영하고 실패 시 재시도하도록 변경했습니다.
+ 주기적으로 MySQL과 검색 인덱스를 비교해 불일치 항목을 다시 등록하도록 했습니다.
+ 오래된 이벤트가 최신 변경을 덮어쓰지 않도록 Outbox ID를 Elasticsearch의 외부 버전으로 활용했습니다.
+ 고객 삭제는 삭제 표시 데이터인 Tombstone을 기록해 처리하도록 했습니다.
  
```java
Customer saved = customerRepository.save(customer);
searchSync.record(saved);
```
```java
index.write(document, Math.multiplyExact(event.getId(), 2L));
event.complete(clock.instant());
```

<br/><br/>

### [ MySQL과 Keycloak 관리자 계정 불일치 문제 ] <br/>

1) 문제 발생 <br/>
+ 관리자 정보는 MySQL에 저장하고 실제 로그인 계정은 Keycloak에 생성했습니다.
+ 기존에는 관리자 생성·수정·삭제 과정에서 Keycloak API를 직접 호출했습니다.
+ 이 과정에서 한쪽 작업만 성공하면 관리자 정보와 실제 로그인 계정이 달라질 수 있었습니다.

<br/><br/>

2) 원인 파악 <br/>
+ MySQL과 Keycloak은 하나의 트랜잭션으로 관리되지 않습니다.
+ Keycloak 계정 생성 후 DB 커밋이 실패하면 로그인 계정만 남을 수 있습니다.
+ 반대로 DB 변경 후 Keycloak 요청에 실패하면 변경 내용이 외부 계정에 반영되지 않을 수 있습니다.

<br/><br/>

3) 문제 해결 <br/>
+ 관리자 데이터와 Outbox 작업을 하나의 MySQL 트랜잭션에 저장하도록 변경했습니다.
+ Scheduler가 관리자 상태에 따라 Keycloak 계정을 생성·수정·삭제하도록 구성했습니다.
+ 계정 생성 시 고유한 accountRef를 Keycloak 사용자 속성에 기록해 재시도 과정에서 동일 계정인지 확인하도록 했습니다.
+ 계정 생성·삭제에 실패해도 작업 상태를 보존하고 다시 시도할 수 있도록 했습니다.
+ 관리자 계정의 상태가 ACTIVE가 아니면 기존 JWT가 있더라도 보호된 API에 접근할 수 없도록 했습니다.
  
```java
admin.setState(Admin.State.PENDING_CREATE);
Admin savedAdmin = adminRepository.save(admin);
accountSync.enqueue(savedAdmin);
```
```java
user.setAttributes(
    Map.of(OWNER_ATTRIBUTE, List.of(admin.getAccountRef()))
);
```


<br/><br/><br/>

### 🔶 테스트 및 검증
Outbox 적용 이후에는 정상적인 데이터 반영뿐 아니라 외부 시스템 장애와 재시도 상황을 확인하는 테스트를 추가했습니다.

### [ 주요 검증 항목 ]

+ 고객 데이터와 Outbox 저장의 트랜잭션 롤백
+ Elasticsearch 장애 발생 후 자동 재시도 및 복구
+ 오래된 이벤트가 최신 고객 정보나 삭제 상태를 덮어쓰지 않는지 확인
+ 검색 인덱스의 누락·불일치 데이터 복구
+ Keycloak 계정 생성·삭제 도중 실패했을 때 재실행 처리
+ 관리자 계정 상태에 따른 JWT 인증 및 API 접근 제어

<br/>

### [ Test Stack ]

```JUnit 5``` ```Spring Boot Test```  ```Mockito``` ```MockMvc```

<br/><br/>


### 🔶 아쉬운 점 및 개선 방향

### [ Outbox 기반 검색 인덱스 반영 방식 ] <br/>

1) 현재 처리 방식 <br/>
+ 고객 데이터 변경과 Outbox 저장을 하나의 트랜잭션으로 처리합니다.
+ Scheduler가 대기 중인 작업을 Elasticsearch에 반영하고 실패한 작업은 재시도합니다.
+ 정합성 점검과 인덱스 재구축 기능도 별도로 구현했습니다.


<br/><br/>

2) 아쉬운 점 <br/>
+ MySQL 변경과 Elasticsearch 반영 사이에는 시간 차이가 발생할 수 있습니다.
+ 장시간 장애가 발생하면 Outbox에 미처리 작업이 쌓일 수 있습니다.
+ 처리 완료된 Outbox 이력도 계속 저장되므로 데이터 보관 및 정리 정책이 필요합니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 운영 환경에서는 미처리 작업 수와 실패 상태를 지속적으로 확인할 수 있는 모니터링을 추가할 수 있습니다.
+ 완료된 Outbox 이벤트는 재처리 및 정합성 복구에 필요한 범위를 고려해 보관 정책을 정의할 수 있습니다.
+ 현재 프로젝트에서는 별도의 메시지 브로커나 CDC 없이 기존 MySQL과 Spring Boot만으로 복구 가능한 구조를 구현하는 데 집중했습니다.

<br/><br/>

----

### [ Keycloak 계정 연동 안정성 ] <br/>

1) 현재 처리 방식 <br/>
+ 관리자 정보와 Outbox 작업을 MySQL에 저장하고 Keycloak 연동은 별도 작업으로 처리합니다.
+ accountRef와 keycloakId를 활용해 계정 연결 관계를 확인합니다.
+ 관리자 상태를 기준으로 서비스 접근을 제어하고 실패한 동기화 작업을 다시 실행할 수 있도록 했습니다.

<br/><br/>

2) 아쉬운 점 <br/>
+ MySQL과 Keycloak은 서로 다른 시스템이므로 모든 작업이 즉시 동일한 상태가 되는 것은 아닙니다.
+ Keycloak 장애가 장기간 지속되거나 계정 연결 관계를 확인할 수 없으면 관리자 확인이 필요합니다.
+ 재시도로 해결하기 어려운 오류는 별도 복구 절차가 필요할 수 있습니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 서비스라면 실패한 작업의 원인과 계정 상태를 확인할 수 있는 관리자용 모니터링 화면을 추가할 수 있습니다.
+ 계정 연결 오류나 장기 장애에 대한 운영 절차를 문서화하고 수동 복구 과정을 더 명확하게 만들 수 있습니다.
+ 현재는 개인 프로젝트 규모를 고려해 관리자 상태 관리와 재시도 기능을 중심으로 구현했습니다.

<br/><br/>

----

### [ 운영 환경을 고려한 설정 및 테스트 보강 ] <br/>

1) 현재 처리 방식 <br/>
+ 로컬 환경에서 MySQL, Elasticsearch, Keycloak을 실행하며 전체 기능을 확인했습니다.
+ 설정값을 별도로 관리할 수 있도록 application.example.properties를 제공하고 있습니다.
+ Outbox의 재시도와 정합성 복구, 외부 시스템 연동을 검증하기 위한 테스트 코드를 작성했습니다.

<br/><br/>

2) 아쉬운 점 <br/>
+ 실제 운영 환경에서는 외부 시스템의 장애뿐 아니라 서버 재시작, 장기간 작업 누적, 동시 요청 증가 등의 상황도 고려해야 합니다.
+ 로컬 테스트만으로 실제 운영 환경의 모든 장애 상황을 검증하기는 어렵습니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 서비스라면 DB 비밀번호, Keycloak 관리자 계정, Elasticsearch 접속 정보 같은 설정값을 환경 변수나 별도 Secret 관리 방식으로 분리하는 것이 좋습니다.
+ 실패 작업 수, 처리 지연 시간 등을 확인하는 모니터링과 알림을 추가할 수 있습니다.
+ 다양한 장애 상황을 반복 검증할 수 있도록 외부 시스템 통합 테스트와 운영 환경 검증을 보강할 수 있습니다.

<br/><br/>

### 🔶 실행 방법

+ Java 17 환경에서 MySQL, Elasticsearch, Keycloak을 준비합니다. Elasticsearch에는 한글 검색에 사용하는 Nori 분석기가 필요합니다.
+ 각 서비스의 접속 정보와 Keycloak 연동 설정을 맞춘 뒤 `./gradlew bootRun`을 실행합니다.
+ 화면을 확인하려면 프론트엔드도 함께 실행해야 합니다.
