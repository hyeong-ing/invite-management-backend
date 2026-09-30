# 🔒 Invite and Management 🔑

<br/>

<p align="center">

  <br/>
  이전 프로젝트에서 회원가입과 로그인을 구현했지만, 로그아웃이나 인증 상태 유지, 권한 처리까지 깊게 다루지 못했습니다. <br/>
  그리고 DB도 H2 위주로만 사용해서 실제 DB를 설계하고 관리하는 경험이 부족하다고 느꼈습니다.<br/>
  그래서 이번 프로젝트에서는 Keycloak을 활용한 인증, Spring Security 기반의 권한 처리,<br/>
  MySQL을 사용한 고객/관리자 데이터 관리까지 경험하기로 했습니다. <br/>
  <br/>

  <br/>

  <img width="800" height="450" alt="image" src="https://github.com/user-attachments/assets/25ed9b61-e432-438e-a6c4-a49c78ee17c9" />

</p>

<br/>
<br/>
<br/>

### 🔶 프로젝트 관련 링크

+ [Blog (프로젝트 기록)](https://post-this.tistory.com/category/%F0%9F%92%BB%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8/%F0%9F%90%A0%ED%9A%8C%EC%9B%90%EA%B0%80%EC%9E%85%20%ED%8E%98%EC%9D%B4%EC%A7%80%F0%9F%90%A0)
+ YouTube (동작화면)
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
+ 고객 / 관리자 / 권한 데이터는 MySQL에 저장하고 고객 검색 데이터는 Elasticsearch에 저장해 검색 기능에 활용합니다.
+ Keycloak Admin Client를 사용해 서비스 관리자 정보와 Keycloak 로그인 계정을 함께 관리합니다.

<br/><br/> 

### 🔶 기술 스택 & 라이브러리
+ 백엔드 : Java 17, Spring Boot 3.5.6
+ 데이터베이스 : MySQL
+ 검색 엔진 : Elasticsearch, Spring Data Elasticsearch
+ 보안 : Spring Security, OAuth2 Resource Server, JWT, Keycloak
+ 관리자 계정 연동 : Keycloak Admin Client
  
<br/><br/>

### 🔶 프로젝트 목표
+ DB에 저장된 초대코드를 조회하고 해당 고객 등급별 안내 페이지로 이동하는 흐름 구현하기.
+ MySQL과 JPA로 고객, 관리자, 권한 데이터를 관리하는 실제 DB 기반 프로젝트 경험하기.
+ Elasticsearch를 활용한 고객 검색 기능 구현하기.
+ Keycloak과 Spring Security를 활용한 로그인/로그아웃 및 권한 기반 접근 제어 경험하기.
+ Docker를 사용해 Keycloak, Elasticsearch 같은 외부 시스템을 실행하고 백엔드와 연동하는 흐름 이해하기.

<br/><br/>

### 🔶 핵심 로직
1) 초대코드 검증 및 등급별 페이지 이동 <br/>
사용자가 4자리 초대코드를 입력하면 백엔드에서 DB에 저장된 초대코드와 일치하는 고객을 조회합니다.

+ 초대코드는 InviteCode 값 객체를 통해 숫자 4자리인지 검증합니다.
+ 유효한 코드라면 고객 정보를 반환합니다.
+ 그리고 프론트엔드에서 고객 등급에 따라 VIP, VVIP, DIAMOND 페이지로 이동합니다.
+ 현재 등급별 안내 페이지는 주소를 직접 입력해도 접근할 수 있으며, 페이지 자체의 접근 제한은 구현하지 않았습니다.

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

3) MySQL 저장 후 Elasticsearch 검색 인덱스 함께 반영 <br/>
고객 원본 데이터는 MySQL에 저장하고, 검색에 사용할 데이터는 Elasticsearch 인덱스에도 함께 반영했습니다.

+ 고객 등록 시 MySQL에 고객 정보를 저장합니다.
+ 저장된 고객 정보를 검색용 Entity로 변환해 Elasticsearch에도 저장합니다.
+ 고객 수정∙삭제 시에도 Elasticsearch 데이터를 함께 갱신하거나 삭제해 검색 결과가 최신 상태를 유지하도록 했습니다.

```java
Customer saved = customerRepository.save(customer);

customerSearchRepository.save(
        customerSearchMapper.toSearchEntity(saved));
```
```java
customer.update(
        request.getName(),
        request.getGrade(),
        request.getPhone(),
        newInviteCode,
        request.getNote()
);

customerSearchRepository.save(
        customerSearchMapper.toSearchEntity(customer)
);
```
```java
customerRepository.delete(customer);
customerSearchRepository.deleteById(id);
```

<br/><br/>

----

4) Elasticsearch 기반 고객 검색 및 초성 검색 <br/>
고객명, 등급, 연락처, 초대코드, 메모를 검색할 수 있도록 Elasticsearch를 사용했습니다.

+ name, note는 한글 검색을 고려해 analyzer를 적용했습니다.
+ 고객 이름에서 초성을 추출해 nameChosung 필드에 저장했습니다.
+ 예를 들어 홍길동은 ㅎㄱㄷ으로 저장되어 초성 검색이 가능합니다.
+ 아래 코드는 검색 조건 중 핵심 부분을 발췌한 예시입니다.

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

+ JWT의 realm role과 DB에 저장된 관리자 role을 Spring Security 권한 형식인 ROLE_ADMIN, ROLE_SUPER_ADMIN으로 사용합니다.
+ DB의 관리자 권한을 조회해 CUSTOMER_SEARCH, CUSTOMER_ADD, CUSTOMER_EDIT, CUSTOMER_DELETE 권한으로 변환합니다.
+ Controller에서는 @PreAuthorize를 사용해 API별 접근 권한을 분리했습니다.

```java
authorities.addAll(extractRealmRoles(jwt));
authorities.addAll(adminAuthorityService.loadAuthorities(jwt));
```
```java
if (admin.getRole() != null) {
    authorities.add(new SimpleGrantedAuthority("ROLE_" + admin.getRole().name()));
}

for (AdminPermission adminPermission : admin.getAdminPermissions()) {
    Permission permission = adminPermission.getPermission();

    if (permission == null || permission.getCode() == null) {
        continue;
    }

    authorities.add(new SimpleGrantedAuthority(
            permission.getCode().trim().toUpperCase()
    ));
}
```


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
+ 고객 정보가 변경되었을 때 Elasticsearch 인덱스가 함께 갱신되지 않으면 검색 결과가 실제 DB와 달라질 수 있었습니다.

<br/><br/>

2) 원인 파악 <br/>
+ MySQL과 Elasticsearch는 서로 다른 저장소이기 때문에, MySQL 저장만으로 Elasticsearch 데이터가 자동 변경되지 않습니다.
+ 고객 등록, 수정, 삭제 시점에 검색 인덱스도 함께 반영해야 했습니다.

<br/><br/>

3) 문제 해결 <br/>
+ 고객 등록 / 수정 / 삭제 시 Elasticsearch 인덱스도 함께 저장하거나 삭제하도록 처리했습니다.
+ MySQL 기준으로 검색 데이터를 다시 맞추는 수동 동기화 API도 추가했습니다.
+ 현재 고객 정보를 모두 저장한 뒤, MySQL에는 없고 Elasticsearch에만 남은 고객 데이터를 삭제합니다.
+ 현재 고객 정보를 저장하는 도중 오류가 발생하면 남은 검색 데이터를 삭제하는 단계는 진행하지 않습니다.
  
```java
Customer saved = customerRepository.save(customer);

customerSearchRepository.save(
        customerSearchMapper.toSearchEntity(saved)
);
```
```java
customer.update(
        request.getName(),
        request.getGrade(),
        request.getPhone(),
        newInviteCode,
        request.getNote()
);

customerSearchRepository.save(
        customerSearchMapper.toSearchEntity(customer)
);
```
```java
customerRepository.delete(customer);
customerSearchRepository.deleteById(id);
```
```java
@PostMapping("/sync")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public ResponseEntity<String> sync() {
    customerService.syncAllToElasticsearch();
    return ResponseEntity.ok("sync ok");
}
```

아래는 수동 동기화 처리 코드입니다.

```java
@Transactional
public void syncAllToElasticsearch() {
    List<Customer> customers = customerRepository.findAll();
    Set<Long> customerIds = customers.stream()
            .map(Customer::getId)
            .collect(Collectors.toSet());

    List<CustomerSearchEntity> staleCustomers = new ArrayList<>();
    for (CustomerSearchEntity document : customerSearchRepository.findAll()) {
        if (!customerIds.contains(document.getId())) {
            staleCustomers.add(document);
        }
    }

    // 현재 고객 정보가 모두 반영된 뒤 불필요한 검색 데이터를 삭제합니다.
    for (Customer customer : customers) {
        customerSearchRepository.save(customerSearchMapper.toSearchEntity(customer));
    }
    if (!staleCustomers.isEmpty()) {
        customerSearchRepository.deleteAll(staleCustomers);
    }
}
```

<br/><br/>

### [ MySQL과 Keycloak 관리자 계정 불일치 문제 ] <br/>

1) 문제 발생 <br/>
+ 관리자 정보는 MySQL에 저장하고, 실제 로그인 계정은 Keycloak에 생성했습니다.
+ 둘 중 하나만 반영되면 DB에는 있지만 로그인할 수 없거나, 로그인은 되지만 서비스 권한을 조회할 수 없는 문제가 생길 수 있었습니다.

<br/><br/>

2) 원인 파악 <br/>
+ MySQL과 Keycloak은 서로 다른 시스템이기 때문에 하나의 트랜잭션으로 자동 처리되지 않습니다.
+ Keycloak 계정과 MySQL 관리자 데이터를 연결할 식별값도 필요했습니다.

<br/><br/>

3) 문제 해결 <br/>
+ 관리자 생성 시 saveAndFlush()로 DB insert와 제약 조건 검사를 먼저 실행한 뒤 Keycloak 사용자 생성을 진행했습니다.
+ 생성된 Keycloak 사용자 id를 admin.keycloakId에 저장해 MySQL 관리자와 Keycloak 계정을 연결했습니다.
+ 관리자 수정 / 삭제 시에도 MySQL 데이터와 Keycloak 사용자 계정을 함께 반영하도록 처리했습니다.
  
```java
Admin savedAdmin = adminRepository.saveAndFlush(admin);

String keycloakId = createKeycloakUser(request);
savedAdmin.setKeycloakId(keycloakId);

adminRepository.flush();
```
```java
adminRepository.flush();
updateKeycloakUser(admin, oldUsername, request);
```
```java
String keycloakId = getKeycloakId(admin, admin.getUsername());

adminRepository.delete(admin);
adminRepository.flush();

deleteKeycloakUser(keycloakId);
```
```java
if (admin.getKeycloakId() == null || admin.getKeycloakId().isBlank()) {
    admin.setKeycloakId(keycloakId);
}
```

<br/><br/><br/>

### 🔶 아쉬운 점 및 개선 방향

### [ 외부 검색 인덱스 반영 방식 ] <br/>

1) 현재 처리 방식 <br/>
+ 고객 원본 데이터는 MySQL에 저장하고, 검색에 사용할 데이터는 Elasticsearch 인덱스에도 함께 저장했습니다.
+ 고객 등록, 수정, 삭제 시점에 Elasticsearch를 바로 호출해 검색 결과가 최신 상태에 가깝게 유지되도록 처리했습니다.
+ 수동 동기화 API는 MySQL의 현재 고객 정보를 검색 엔진에 다시 저장하고, MySQL에 없는 검색 데이터를 삭제합니다.


<br/><br/>

2) 아쉬운 점 <br/>
+ MySQL과 Elasticsearch는 서로 다른 저장소이기 때문에 하나의 트랜잭션으로 완전히 묶이지는 않습니다.
+ 한쪽 작업이 성공한 뒤 다른 쪽 작업이나 DB 커밋이 실패하면 두 저장소의 데이터가 어긋날 수 있습니다.
+ 현재 수동 동기화는 고객 수가 적고, 동기화 중 다른 고객 변경 작업이 없는 상황을 전제로 합니다.
+ 동기화 도중의 동시 변경과 일부 반영 실패에 대한 자동 복구는 추가로 보완할 부분입니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 서비스라면 고객 데이터 변경 시 Elasticsearch를 바로 호출하기보다 변경 이벤트를 별도로 저장하는 방식을 고려할 수 있습니다.
+ 예를 들어 변경 이력을 DB에 별도로 저장하는 Outbox Pattern이나 메시지 큐를 이용해 검색 데이터 반영 실패를 추적하고 재시도하는 구조를 고려할 수 있습니다.
+ Outbox Pattern과 메시지 큐는 향후 개선 방안이며, 현재 프로젝트에는 적용하지 않았습니다.
+ 현재 프로젝트에서는 개인 프로젝트 규모를 고려해 CRUD 시점에 즉시 반영하고, 필요 시 수동 동기화 API로 복구할 수 있는 단순한 구조를 선택했습니다.

<br/><br/>

----

### [ Keycloak 계정 연동 안정성 ] <br/>

1) 현재 처리 방식 <br/>
+ 관리자 정보는 MySQL에 저장하고 실제 로그인 계정은 Keycloak에 생성했습니다.
+ 관리자 생성 시 `saveAndFlush()`로 DB insert와 제약 조건 검사를 먼저 실행한 뒤 Keycloak 사용자 생성을 진행했습니다.
+ 생성된 Keycloak 사용자 id를 `admin.keycloakId`에 저장해 MySQL 관리자 데이터와 Keycloak 계정을 연결했습니다.

<br/><br/>

2) 아쉬운 점 <br/>
+ MySQL과 Keycloak 역시 서로 다른 시스템이기 때문에 완전히 하나의 트랜잭션으로 처리되지는 않습니다.
+ 예를 들어 Keycloak 사용자 생성은 성공했지만 이후 DB 커밋이 실패하거나, 수정 과정에서 일부 정보만 반영되는 상황이 생길 수 있습니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 서비스라면 관리자 생성, 수정, 삭제 요청을 상태값으로 관리하는 방식을 고려할 수 있습니다.
+ 예를 들어 관리자 생성 요청을 먼저 DB에 저장하고, Keycloak 연동 작업은 별도 작업 큐에서 처리한 뒤 성공 시 ACTIVE, 실패 시 FAILED 상태로 관리할 수 있습니다.
+ 이렇게 하면 Keycloak 장애나 중간 실패 상황을 추적하고 재시도하기 쉬워집니다.
+ 현재 프로젝트에서는 구조가 지나치게 무거워지지 않도록 flush()와 keycloakId를 활용해 두 시스템의 연결 순서를 명확히 하는 방식으로 처리했습니다.

<br/><br/>

----

### [ 운영 환경을 고려한 설정 및 테스트 보강 ] <br/>

1) 현재 처리 방식 <br/>
+ 개발 과정에서는 로컬 환경에서 MySQL, Elasticsearch, Keycloak을 실행하며 기능 구현과 흐름 확인에 집중했습니다.
+ 인증, 권한, 고객 검색, 관리자 관리 기능이 연결되는 전체 흐름을 우선적으로 구현했습니다.
+ 수동 동기화 보완 후, 저장소 동작을 대신하는 Mock을 사용한 단위 테스트 6개를 추가했고 모두 통과했습니다.
+ 현재 고객 갱신·추가, 검색에만 남은 고객 삭제, 빈 데이터 처리, 조회·저장 실패 시의 동작을 확인했습니다.
+ 실제 MySQL과 Elasticsearch를 연결한 통합 검증은 아직 진행하지 않았으며, 기존 앱 구동 테스트는 DB 연결 설정 부족으로 실행에 실패했습니다.

<br/><br/>

2) 아쉬운 점 <br/>
+ 현재 프로젝트는 로컬 실행과 시연을 기준으로 작성되어 있어, 실제 운영 환경을 기준으로 보면 설정값 관리와 외부 시스템 연동 테스트를 더 보강할 여지가 있습니다.
+ 특히 DB, Elasticsearch, Keycloak처럼 외부 시스템에 의존하는 기능은 환경에 따라 동작 차이가 생길 수 있습니다.

<br/><br/>

3) 개선 방향 <br/>
+ 실제 서비스라면 DB 비밀번호, Keycloak 관리자 계정, Elasticsearch 접속 정보 같은 설정값을 환경 변수나 별도 Secret 관리 방식으로 분리하는 것이 좋습니다.
+ 또한 Testcontainers 등을 활용해 MySQL과 Elasticsearch 연동 흐름을 실제 환경과 가깝게 검증할 수 있습니다.
+ Keycloak의 경우 테스트 컨테이너나 테스트 Realm 구성을 추가해 인증 서버 연동 흐름까지 단계적으로 검증하는 방향으로 확장할 수 있습니다.
+ 현재 프로젝트에서는 개인 학습과 기능 구현을 우선했기 때문에, 추후에는 설정 분리와 외부 시스템 통합 테스트를 보강하는 방향으로 개선할 수 있습니다.

<br/><br/>

### 🔶 실행 방법

+ Java 17 환경에서 MySQL, Elasticsearch, Keycloak을 준비합니다. Elasticsearch에는 한글 검색에 사용하는 Nori 분석기가 필요합니다.
+ 각 서비스의 접속 정보와 Keycloak 연동 설정을 맞춘 뒤 `./gradlew bootRun`을 실행합니다.
+ 화면을 확인하려면 프론트엔드도 함께 실행해야 합니다.
