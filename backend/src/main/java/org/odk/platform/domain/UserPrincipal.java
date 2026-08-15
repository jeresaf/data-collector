package org.odk.platform.domain;

import java.util.UUID;

public interface UserPrincipal {
    UUID getId();
    String getUsername();
    String getEmail();
    String getFullName();
    boolean isActive();
    boolean isVerified();
}