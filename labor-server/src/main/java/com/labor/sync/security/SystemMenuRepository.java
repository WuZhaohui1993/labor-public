package com.labor.sync.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SystemMenuRepository extends JpaRepository<SystemMenu, Long> {
    List<SystemMenu> findAllByOrderBySortNoAscIdAsc();

    boolean existsByParentId(Long parentId);

    boolean existsByRouteNameIgnoreCase(String routeName);

    boolean existsByRouteNameIgnoreCaseAndIdNot(String routeName, Long id);

    boolean existsByRoutePath(String routePath);

    boolean existsByRoutePathAndIdNot(String routePath, Long id);
}
