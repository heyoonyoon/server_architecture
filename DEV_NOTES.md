# 개발하면서 한 고민과 해결 방법

## 1. 학생 추가 요청에 학생 정보를 어떻게 담을까

### 고민
학생 추가(Add) 기능은 결국 학생 정보 한 명분을 Data 서버로 보내는 것이다.
처음에는 추가 요청 메시지에 학생 정보 필드를 하나하나 다시 적었다.

```protobuf
message AddStudentListRepositoryRequest {
  string student_id = 1;
  string name = 2;
  string department = 3;
  repeated string completed_course_ids = 4;
}
```

그런데 이 필드들은 이미 정의해둔 `StudentInfo`와 완전히 같다.

```protobuf
message StudentInfo {
  string student_id = 1;
  string name = 2;
  string department = 3;
  repeated string completed_course_ids = 4;
}
```

이렇게 같은 내용을 두 번 적으면 다음 문제가 생긴다.

- 학생 정보에 필드가 하나 늘어나면(예: 학년) `StudentInfo`와 추가 요청 메시지를 둘 다 고쳐야 한다. 한쪽만 고치면 조회와 추가의 학생 정보 형식이 달라진다.
- 생성되는 Java 클래스도 따로 생겨서, 조회로 받은 `StudentInfo`를 추가 요청에 그대로 쓸 수 없고 필드를 하나씩 옮겨 적어야 한다.

### 해결 방법
protobuf는 메시지 안에 다른 메시지를 필드로 넣을 수 있다.
그래서 추가 요청 메시지가 학생 정보 필드를 직접 갖는 대신, 이미 정의한 `StudentInfo`를 통째로 하나 갖도록 했다.

```protobuf
message AddStudentRepositoryRequest {
  StudentInfo student = 1;
}

message AddStudentRepositoryResponse {
  bool success = 1;
}

service DataServerService {
  rpc AddStudentRepository(AddStudentRepositoryRequest)
      returns (AddStudentRepositoryResponse);
}
```

조회 응답에서 이미 `repeated StudentInfo students`로 같은 방식을 쓰고 있다.
조회는 `StudentInfo` 여러 개(`repeated`), 추가는 `StudentInfo` 하나라는 차이만 있다.

Java 코드에서는 이렇게 쓴다.

```java
StudentInfo student = StudentInfo.newBuilder()
        .setStudentId("20250001")
        .setName("Hong Gildong")
        .setDepartment("CS")
        .build();

AddStudentRepositoryRequest request = AddStudentRepositoryRequest.newBuilder()
        .setStudent(student)
        .build();
```

Data 서버에서는 `request.getStudent()`로 `StudentInfo`를 꺼내 쓴다.

### 결과
- 학생 정보의 형식은 `StudentInfo` 한 곳에서만 정의한다. 필드가 바뀌어도 `StudentInfo`만 고치면 조회와 추가에 함께 반영된다.
- 조회와 추가가 같은 `StudentInfo` 클래스를 쓰므로, 계층 사이에서 학생 정보를 옮겨 적는 코드가 줄어든다.
