package com.sumedha.commerce.auth.entity;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="refresh_tokens") public class RefreshToken {
 @Id @Column(name="token_id") private UUID id; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private User user; @Column(name="refresh_token_hash",nullable=false,unique=true) private String tokenHash; @Column(name="expires_at",nullable=false) private Instant expiresAt; @Column(nullable=false) private boolean revoked; @Column(name="created_at",nullable=false) private Instant createdAt;
 protected RefreshToken(){} public RefreshToken(User u,String h,Instant e){id=UUID.randomUUID();user=u;tokenHash=h;expiresAt=e;createdAt=Instant.now();} public User getUser(){return user;} public String getTokenHash(){return tokenHash;} public boolean isRevoked(){return revoked;} public boolean isExpired(){return !expiresAt.isAfter(Instant.now());} public void revoke(){revoked=true;}
}
