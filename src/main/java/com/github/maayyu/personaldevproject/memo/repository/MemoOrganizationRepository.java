package com.github.maayyu.personaldevproject.memo.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.maayyu.personaldevproject.memo.entity.MemoOrganization;

public interface MemoOrganizationRepository extends JpaRepository<MemoOrganization, Long> {

}
