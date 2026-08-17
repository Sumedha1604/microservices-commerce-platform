package com.sumedha.commerce.auth.entity;

import com.sumedha.commerce.auth.enums.*; import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="users") public class User {
 @Id @Column(name="user_id", nullable=false) private UUID id; @Column(nullable=false, unique=true, length=320) private String email;
 @Column(name="password_hash", nullable=false) private String passwordHash; @Enumerated(EnumType.STRING) @Column(nullable=false) private UserRole role;
 @Column(name="is_verified", nullable=false) private boolean verified; @Enumerated(EnumType.STRING) @Column(nullable=false) private UserStatus status;
 @Column(name="created_at", nullable=false) private Instant createdAt; @Column(name="updated_at", nullable=false) private Instant updatedAt;
 protected User() {} public User(String email,String passwordHash) { id=UUID.randomUUID(); this.email=email; this.passwordHash=passwordHash; role=UserRole.CUSTOMER; status=UserStatus.ACTIVE; createdAt=Instant.now(); updatedAt=createdAt; }
 @PreUpdate void touch(){updatedAt=Instant.now();} public UUID getId(){return id;} public String getEmail(){return email;} public String getPasswordHash(){return passwordHash;} public UserRole getRole(){return role;} public boolean isVerified(){return verified;} public UserStatus getStatus(){return status;} public void setStatus(UserStatus s){status=s;} public void verify(){verified=true;} public void changePassword(String hash){passwordHash=hash;}
}
