package org.odk.platform.repository;

import org.odk.platform.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, java.util.UUID> {

    Optional<RefreshToken> findByToken(String token);

    void deleteByUserUsername(String username);

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user.username = :username AND rt.revoked = false AND rt.expiresAt > :now")
    List<RefreshToken> findValidByUserUsername(@Param("username") String username, @Param("now") Instant now);

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.appUser.username = :username AND rt.revoked = false AND rt.expiresAt > :now")
    List<RefreshToken> findValidByAppUserUsername(@Param("username") String username, @Param("now") Instant now);

    void deleteByUserId(java.util.UUID userId);

    void deleteByAppUserId(java.util.UUID appUserId);
}
