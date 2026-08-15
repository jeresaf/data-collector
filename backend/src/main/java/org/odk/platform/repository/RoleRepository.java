package org.odk.platform.repository;

import org.odk.platform.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByNameAndProjectIsNull(String name);

    List<Role> findByProjectIsNullAndIsActiveTrueOrderByDisplayNameAsc();
}
