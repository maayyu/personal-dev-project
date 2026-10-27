package com.github.maayyu.personaldevproject.memo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.maayyu.personaldevproject.memo.entity.Memo;

public interface MemoRepository extends JpaRepository<Memo, Long> {
    Optional<Memo> findByIdAndUser_Id(Long id, Long userId);

}
