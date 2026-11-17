package com.github.maayyu.personaldevproject.organization.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.maayyu.personaldevproject.organization.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    // IDと所有者からOrganizationを取得する取得メソッド
    Optional<Organization> findByIdAndUserId(Long id, Long userId);

    // 所有者IDでOrganizationを取得するメソッド（名前とIDの昇順）
    List<Organization> findAllByUserIdOrderByNameAscIdAsc(Long userId);

    // 親IDと所有者IDでOrganizationの存在を確認するメソッド
    boolean existsByParentIdAndUserId(Long parentId, Long userId);

    // 親IDと所有者IDでOrganizationを取得するメソッド（名前とIDの昇順）
    List<Organization> findAllByParentIdAndUserIdOrderByNameAscIdAsc(Long parentId, Long userId);

    // 所有者ID、親ID、および正規化された名前でOrganizationを取得するメソッド
    Optional<Organization> findByUserIdAndParentIdAndNormalizedName(Long userId, Long parentId, String normalizedName);

    // 所有者ID、親IDがnull、および正規化された名前でOrganizationを取得するメソッド
    Optional<Organization> findByUserIdAndParentIdIsNullAndNormalizedName(Long userId, String normalizedName);

}
