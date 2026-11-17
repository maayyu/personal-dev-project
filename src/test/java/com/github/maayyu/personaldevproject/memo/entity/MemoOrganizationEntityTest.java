package com.github.maayyu.personaldevproject.memo.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.github.maayyu.personaldevproject.PostgresTestConfiguration;
import com.github.maayyu.personaldevproject.organization.entity.Organization;
import com.github.maayyu.personaldevproject.user.entity.User;
import com.github.maayyu.personaldevproject.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
public class MemoOrganizationEntityTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test // MemoOrganization Entityの保存・再取得テスト
    void testSaveAndRetrieveMemoOrganization() {
        // ①Userを作成して保存
        User user = userRepository.save(new User("memo-test@example.com", "test-password-hash"));

        // ②MemoとOrganizationを作成して保存
        Memo memo = new Memo(user, "Javaの学習", "Spring Bootについて学習");
        Organization organization = new Organization(user, null, "Java", "java");
        entityManager.persist(memo);
        entityManager.persist(organization);

        // ③ CURRENTの関連付けを作成・保存
        MemoOrganization relation = new MemoOrganization(memo, organization, RelationType.CURRENT, null);
        entityManager.persist(relation);
        Long relationId = relation.getId();
        Long memoId = memo.getId();
        Long organizationId = organization.getId();

        // ③ DBにSQLを反映
        entityManager.flush();
        Instant createdAtAfterSave = relation.getCreatedAt();
        assertNotNull(createdAtAfterSave);

        // ④ 永続化コンテキストをクリア
        entityManager.clear();

        // ⑤ MemoOrganizationを再取得
        MemoOrganization retrieved = entityManager.find(MemoOrganization.class, relationId);

        // ⑤ 保存内容を検証
        assertNotNull(retrieved);
        assertEquals(relationId, retrieved.getId());

        assertEquals(memoId, retrieved.getMemo().getId());
        assertEquals(organizationId,
                retrieved.getOrganization().getId());

        assertEquals(RelationType.CURRENT,
                retrieved.getRelationType());

        assertNull(retrieved.getAiProcessId());
        assertNotNull(retrieved.getCreatedAt());
        assertEquals(createdAtAfterSave, retrieved.getCreatedAt());

    }

    @Test // DB基本設計§2：同じMemoが複数Organizationに所属できる
    void testSaveMultipleOrganizationsForSameMemo() {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization java = saveOrganization(user, "Java");
        Organization spring = saveOrganization(user, "Spring Boot");
        entityManager.persist(new MemoOrganization(memo, java, RelationType.CURRENT, null));
        entityManager.persist(new MemoOrganization(memo, spring, RelationType.CURRENT, null));
        entityManager.flush();
        entityManager.clear();

        List<MemoOrganization> retrieved = findRelations(memo.getId());

        assertThat(retrieved).hasSize(2);
        assertThat(retrieved).extracting(relation -> relation.getOrganization().getId())
                .containsExactlyInAnyOrder(java.getId(), spring.getId());
        assertThat(retrieved).extracting(MemoOrganization::getRelationType)
                .containsOnly(RelationType.CURRENT);
    }

    @ParameterizedTest // DB基本設計§5.4：両種別を文字列で保存し、AI由来CURRENTも参照元を保持する
    @EnumSource(RelationType.class)
    void testSaveAndRetrieveEachRelationType(RelationType relationType) {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        Long aiProcessId = saveInitialAiProcess(memo);
        MemoOrganization relation = new MemoOrganization(memo, organization, relationType, aiProcessId);
        entityManager.persist(relation);
        entityManager.flush();
        Long relationId = relation.getId();
        entityManager.clear();

        // Enumの再取得に加えて、ordinalではなくDDLの文字列値で保存されていることを確認する。
        Object storedType = entityManager.createNativeQuery(
                "SELECT relation_type FROM memo_organizations WHERE id = :relationId")
                .setParameter("relationId", relationId)
                .getSingleResult();
        MemoOrganization retrieved = entityManager.find(MemoOrganization.class, relationId);

        assertEquals(relationType.name(), storedType);
        assertNotNull(retrieved);
        assertEquals(memo.getId(), retrieved.getMemo().getId());
        assertEquals(organization.getId(), retrieved.getOrganization().getId());
        assertEquals(relationType, retrieved.getRelationType());
        assertEquals(aiProcessId, retrieved.getAiProcessId());
    }

    @Test // DB基本設計§5.4：同じMemo・Organizationでも種別が異なれば共存できる
    void testAllowDifferentRelationTypesForSameMemoAndOrganization() {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        Long aiProcessId = saveInitialAiProcess(memo);
        entityManager.persist(new MemoOrganization(memo, organization, RelationType.CURRENT, aiProcessId));
        entityManager.persist(new MemoOrganization(memo, organization, RelationType.AI_SUGGESTED, aiProcessId));
        entityManager.flush();
        entityManager.clear();

        List<MemoOrganization> retrieved = findRelations(memo.getId());

        assertThat(retrieved).hasSize(2);
        assertThat(retrieved).extracting(MemoOrganization::getId).doesNotHaveDuplicates();
        assertThat(retrieved).extracting(MemoOrganization::getRelationType)
                .containsExactlyInAnyOrder(RelationType.CURRENT, RelationType.AI_SUGGESTED);
        assertThat(retrieved).extracting(relation -> relation.getOrganization().getId())
                .containsOnly(organization.getId());
        assertThat(retrieved).extracting(MemoOrganization::getAiProcessId).containsOnly(aiProcessId);
    }

    @ParameterizedTest // V1の3列UNIQUE：ai_process_idが異なっても同じ所属・種別は重複できない
    @EnumSource(RelationType.class)
    void testRejectDuplicateAssignment(RelationType relationType) {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        Long firstSource = relationType == RelationType.AI_SUGGESTED ? saveInitialAiProcess(memo) : null;
        Long secondSource = saveInitialAiProcess(memo);
        entityManager.persist(new MemoOrganization(memo, organization, relationType, firstSource));
        entityManager.flush();

        assertPostgresConstraint("23505", "uq_memo_organizations_assignment", () -> {
            entityManager.persist(new MemoOrganization(memo, organization, relationType, secondSource));
            entityManager.flush();
        });
    }

    @Test // V1の条件付きCHECK：AI_SUGGESTEDにはai_process_idが必須
    void testRejectAiSuggestedWithoutAiProcessId() {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        entityManager.flush();

        assertPostgresConstraint("23514", "chk_memo_organizations_ai_suggested_source", () -> {
            entityManager.persist(new MemoOrganization(memo, organization, RelationType.AI_SUGGESTED, null));
            entityManager.flush();
        });
    }

    @ParameterizedTest // 各FKを単独で壊し、他のFK・CHECK違反と区別する
    @CsvSource({
            "memo_id, fk_memo_organizations_memo",
            "organization_id, fk_memo_organizations_organization",
            "ai_process_id, fk_memo_organizations_ai_process"
    })
    void testRejectNonexistentForeignKey(String column, String constraintName) {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        Long aiProcessId = saveInitialAiProcess(memo);
        // V1のSEQUENCEは1から採番する。新規コンテナ内に存在しないIDを1列だけ指定する。
        Long missingId = Long.MIN_VALUE;
        Long memoId = column.equals("memo_id") ? missingId : memo.getId();
        Long organizationId = column.equals("organization_id") ? missingId : organization.getId();
        Long sourceId = column.equals("ai_process_id") ? missingId : aiProcessId;

        // JPAの関連解決を介さず、実DBが存在しない参照先を拒否することを検証する。
        assertPostgresConstraint("23503", constraintName,
                () -> entityManager.createNativeQuery("""
                        INSERT INTO memo_organizations (memo_id, organization_id, relation_type, ai_process_id)
                        VALUES (:memoId, :organizationId, 'AI_SUGGESTED', :aiProcessId)
                        """)
                        .setParameter("memoId", memoId)
                        .setParameter("organizationId", organizationId)
                        .setParameter("aiProcessId", sourceId)
                        .executeUpdate());
    }

    @ParameterizedTest // Organization詳細設計§7.4・7.5：両種別の参照がOrganization削除を妨げる
    @EnumSource(RelationType.class)
    void testRejectDeleteOrganizationReferencedByMemo(RelationType relationType) {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Organization organization = saveOrganization(user, "Java");
        Long aiProcessId = relationType == RelationType.AI_SUGGESTED ? saveInitialAiProcess(memo) : null;
        entityManager.persist(new MemoOrganization(memo, organization, relationType, aiProcessId));
        entityManager.flush();
        Long organizationId = organization.getId();
        // 関連Entityを切り離し、JPAの状態検査ではなくDBの削除拒否を検証する。
        entityManager.clear();
        Organization referencedOrganization = entityManager.find(Organization.class, organizationId);

        assertPostgresConstraint("23503", "fk_memo_organizations_organization", () -> {
            entityManager.remove(referencedOrganization);
            entityManager.flush();
        });
    }

    @Test // DB基本設計§5.4・Memo詳細設計§4.4：Memo削除時はDBのCASCADEで関連行を削除
    void testDeleteMemoCascadesOnlyItsRelations() {
        User user = saveUser();
        Memo memo = saveMemo(user);
        Memo otherMemo = saveMemo(user);
        Organization java = saveOrganization(user, "Java");
        Organization spring = saveOrganization(user, "Spring Boot");
        Long aiProcessId = saveInitialAiProcess(memo);
        entityManager.persist(new MemoOrganization(memo, java, RelationType.CURRENT, aiProcessId));
        entityManager.persist(new MemoOrganization(memo, java, RelationType.AI_SUGGESTED, aiProcessId));
        entityManager.persist(new MemoOrganization(memo, spring, RelationType.CURRENT, null));
        MemoOrganization otherRelation = new MemoOrganization(otherMemo, java, RelationType.CURRENT, null);
        entityManager.persist(otherRelation);
        entityManager.flush();
        Long otherRelationId = otherRelation.getId();
        entityManager.clear();
        assertThat(findRelations(memo.getId())).hasSize(3);
        // 事前確認で読み込んだ関連Entityを切り離し、DBの削除動作を検証する。
        entityManager.clear();

        // 関連行を明示削除せず、Memoだけを削除してDBのON DELETE CASCADEを確認する。
        entityManager.remove(entityManager.find(Memo.class, memo.getId()));
        entityManager.flush();
        entityManager.clear();

        assertNull(entityManager.find(Memo.class, memo.getId()));
        assertThat(findRelations(memo.getId())).isEmpty();
        MemoOrganization survivingRelation = entityManager.find(MemoOrganization.class, otherRelationId);
        assertNotNull(survivingRelation);
        assertEquals(otherMemo.getId(), survivingRelation.getMemo().getId());
        assertEquals(java.getId(), survivingRelation.getOrganization().getId());
        assertNotNull(entityManager.find(Organization.class, java.getId()));
        assertNotNull(entityManager.find(Organization.class, spring.getId()));
    }

    private User saveUser() {
        return userRepository.save(new User("memo-organization-test@example.test", "test-password-hash"));
    }

    private Memo saveMemo(User user) {
        Memo memo = new Memo(user, "Javaの学習", "Spring Bootについて学習");
        entityManager.persist(memo);
        return memo;
    }

    private Organization saveOrganization(User user, String name) {
        Organization organization = new Organization(user, null, name, name);
        entityManager.persist(organization);
        return organization;
    }

    private Long saveInitialAiProcess(Memo memo) {
        entityManager.flush();
        // AiProcess Entityは未実装。V1の必須項目を満たすINITIAL成功履歴を同一TXに準備する。
        return ((Number) entityManager.createNativeQuery("""
                INSERT INTO ai_processes (memo_id, process_type, status)
                VALUES (:memoId, 'INITIAL', 'COMPLETED')
                RETURNING id
                """)
                .setParameter("memoId", memo.getId())
                .getSingleResult()).longValue();
    }

    private List<MemoOrganization> findRelations(Long memoId) {
        return entityManager.createQuery(
                "SELECT relation FROM MemoOrganization relation WHERE relation.memo.id = :memoId",
                MemoOrganization.class)
                .setParameter("memoId", memoId)
                .getResultList();
    }

    private void assertPostgresConstraint(String sqlState, String constraintName, Executable operation) {
        // 各テスト・パラメータ化ケースは@DataJpaTestの独立したトランザクションで実行する。
        PersistenceException error = assertThrows(PersistenceException.class, operation);
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;
        assertEquals(sqlState, sqlError.getSQLState());
        assertNotNull(sqlError.getServerErrorMessage());
        assertEquals(constraintName, sqlError.getServerErrorMessage().getConstraint());
        // 制約違反後は例外情報だけを検証し、ロールバックまでDBにアクセスしない。
    }
}
