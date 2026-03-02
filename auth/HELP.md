🧩 Naqqa Auth Library

A modular authentication and authorization library for Java Spring Boot applications.
Provides JWT-based authentication, BCrypt password hashing, role-based access control, and ready-to-use endpoints for registration, login, and password management.

🚀 Features

✅ JWT Authentication

Generates secure JSON Web Tokens for authenticated users

Configurable secret and expiration time via environment or application.properties

Token validation filter integrated into Spring Security chain

✅ Password Encryption

Uses BCryptPasswordEncoder for strong password hashing

Automatically hashes passwords on registration and password change

✅ Role-Based Access Control

Supports default roles: USER, ADMIN

Use annotations like:

```
@PreAuthorize("hasRole('ADMIN')")
```

or multiple roles:

```
@PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
```

✅ Endpoints

Endpoint	Method	Description

```/api/auth/register```	POST	Register a new user

```/api/auth/login```	POST	Authenticate and receive a JWT

```/api/auth/change-password```	POST	Change user password securely

✅ PostgreSQL Ready

Integrates directly with PostgreSQL using JPA / Hibernate

Auto-creates users table with schema compatible entities

✅ Configurable via ENV

JWT secret and expiration configurable via environment variables

Supports local fallback values

⚙️ Installation
Option 1: As Maven Dependency (local/private repo)

Build and install into local Maven repository:

mvn clean install


Add dependency to your consuming project’s pom.xml:

### 📦 Add Naqqa Auth Library

```xml
<repository>
  <id>github-Naqqa-Software-naqqa-backend-services</id>
  <url>https://maven.pkg.github.com/Naqqa-Software/naqqa-backend-services</url>
</repository>

<dependency>
  <groupId>com.naqqa</groupId>
  <artifactId>naqqa-auth</artifactId>
  <version>0.0.35</version>
</dependency>
```

Option 2: As Git Submodule

If you prefer to include it directly from Git:

git submodule add https://github.com/Naqqa-Software/naqqa-backend-services.git auth


Then import it in your main project’s parent pom.xml using <modules> or direct path dependency.

🧱 Configuration

Add the following properties to your application.properties (or .yml):
```
spring.application.name=my-app
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5432/naqqa_backend_services
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.jpa.hibernate.ddl-auto=update

# JWT Configuration
jwt.secret=${JWT_SECRET:YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=}
jwt.expiration=${JWT_EXPIRATION:86400000} # 24h default
```

The JWT_SECRET must be Base64-encoded 256 bits minimum.
You can generate one safely in Java:
```
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Base64;

public class GenerateJwtKey {
    public static void main(String[] args) {
        System.out.println(Base64.getEncoder().encodeToString(
            Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded()
        ));
    }
}
```
🔐 Usage Examples
Register a new user

POST ```/api/auth/register```

```
{
"email": "john.doe@example.com",
"password": "StrongPass123!",
"fullName": "John Doe"
}
```


✅ Response:

```
{
"token": "eyJhbGciOiJIUzI1NiIsInR5..."
}
```

Login

POST ```/api/auth/login```

```
{
"email": "john.doe@example.com",
"password": "StrongPass123!"
}
```


✅ Response:

```
{
"token": "eyJhbGciOiJIUzI1NiIsInR5..."
}
```

Change Password

POST ```/api/auth/change-password```

```
{
"email": "john.doe@example.com",
"oldPassword": "StrongPass123!",
"newPassword": "EvenStrongerPass456!"
}
```


✅ Response:

```
{
"message": "Password updated for user: john.doe@example.com"
}
```

🧰 Development Notes

Java version: 17+ (tested with JDK 25)

Spring Boot: 3.5.7

Database: PostgreSQL 14+

🧑‍💻 Integration Tips

Once imported, endpoints under /api/auth/** are public, while all other routes are secured by default.

To protect your own endpoints:

```
@PreAuthorize("hasRole('ADMIN')")
@GetMapping("/admin/dashboard")
public String getDashboard() {
return "Secure content";
}
```

To extract authenticated user info from JWT:

```
@Autowired
private JwtService jwtService;

String email = jwtService.extractUsername(token);
```

📜 License

This library is proprietary to Naqqa Software and intended for internal and partner use.