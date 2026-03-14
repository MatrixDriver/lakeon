# Java / Spring Boot 连接指南

## Spring Boot + JDBC

### 1. 依赖（pom.xml）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### 2. 配置（application.yml）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require
    username: user_xxx
    password: PASSWORD
    hikari:
      connection-timeout: 10000   # 首次唤醒可能需要 3-5 秒
      maximum-pool-size: 5
```

### 3. 使用

```java
@RestController
public class HelloController {

    private final JdbcTemplate jdbc;

    public HelloController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/db-version")
    public String version() {
        return jdbc.queryForObject("SELECT version()", String.class);
    }
}
```

## Spring Boot + JPA

### 1. 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### 2. 配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require
    username: user_xxx
    password: PASSWORD
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

### 3. 实体 + Repository

```java
@Entity
@Table(name = "todos")
public class Todo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private boolean done = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    // getters, setters
}

public interface TodoRepository extends JpaRepository<Todo, Long> {
    List<Todo> findByDoneFalse();
}

@RestController
@RequestMapping("/api/todos")
public class TodoController {
    private final TodoRepository repo;

    public TodoController(TodoRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public Todo create(@RequestBody Todo todo) {
        return repo.save(todo);
    }

    @GetMapping
    public List<Todo> list() {
        return repo.findAll();
    }
}
```

## 原生 JDBC

```java
String url = "jdbc:postgresql://pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require";
try (Connection conn = DriverManager.getConnection(url, "user_xxx", "PASSWORD");
     Statement stmt = conn.createStatement();
     ResultSet rs = stmt.executeQuery("SELECT version()")) {
    if (rs.next()) {
        System.out.println(rs.getString(1));
    }
}
```

## 注意事项

- JDBC URL 中 `options=endpoint%3D<db-name>` 是必须的，用于 Proxy 路由
- HikariCP 的 `connection-timeout` 建议设为 10s，因为休眠数据库首次唤醒需要 ~3 秒
- 生产环境用环境变量注入密码，不要硬编码
