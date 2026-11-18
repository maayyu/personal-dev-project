package com.github.maayyu.personaldevproject.memo.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.github.maayyu.personaldevproject.PostgresTestConfiguration;
import com.github.maayyu.personaldevproject.memo.entity.Memo;
import com.github.maayyu.personaldevproject.memo.entity.MemoOrganization;
import com.github.maayyu.personaldevproject.memo.entity.RelationType;
import com.github.maayyu.personaldevproject.organization.entity.Organization;
import com.github.maayyu.personaldevproject.user.entity.User;
import com.github.maayyu.personaldevproject.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
public class MemoOrganizationRepositoryTest {

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private MemoOrganizationRepository memoOrganizationRepository;

        @Autowired
        private EntityManager entityManager;

        @Test // Repositoryの保存・再取得テスト
        void testSaveAndRetrieveMemoOrganization() {
                // ①Userを作成して保存
                User user = userRepository.save(new User("memo-test@example.com", "test-password-hash"));

                // ②MemoとOrganizationを作成して保存
                Memo memo = new Memo(user, "Javaの学習", "Spring Bootについて学習");
                Organization organization = new Organization(user, null, "Java",
                                "java");
                entityManager.persist(memo);
                entityManager.persist(organization);

                // ③ CURRENTの関連付けを作成・保存
                MemoOrganization relation = new MemoOrganization(memo, organization, RelationType.CURRENT, null);

                MemoOrganization saved = memoOrganizationRepository.save(relation);

                Long relationId = saved.getId();
                Long memoId = memo.getId();
                Long organizationId = organization.getId();

                // ④ DBにSQLを反映
                entityManager.flush();

                Instant createdAtAfterSave = saved.getCreatedAt();
                assertNotNull(createdAtAfterSave);

                // ⑤ 永続化コンテキストをクリア
                entityManager.clear();

                // ⑥ RepositoryからDBの内容を再取得
                MemoOrganization retrieved = memoOrganizationRepository.findById(relationId).orElseThrow();

                // ⑦ 再取得した内容を検証
                assertNotNull(retrieved);
                assertEquals(relationId, retrieved.getId());

                assertEquals(memoId, retrieved.getMemo().getId());
                assertEquals(organizationId, retrieved.getOrganization().getId());

                assertEquals(RelationType.CURRENT, retrieved.getRelationType());

                assertNull(retrieved.getAiProcessId());
                assertNotNull(retrieved.getCreatedAt());
                assertEquals(createdAtAfterSave, retrieved.getCreatedAt());

        }

}
