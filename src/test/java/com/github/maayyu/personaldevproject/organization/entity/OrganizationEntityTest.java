package com.github.maayyu.personaldevproject.organization.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.github.maayyu.personaldevproject.PostgresTestConfiguration;
import com.github.maayyu.personaldevproject.user.entity.User;
import com.github.maayyu.personaldevproject.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
public class OrganizationEntityTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test // ルートOrganizationの保存と再取得テスト
    void testSaveAndRetrieveRootOrganization() {
        // ①Userを作成して保存
        User user = new User("memo-test@example.com",
                "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②Organizationを作成して保存
        Organization organization = new Organization(savedUser, null, "test 技術", "test 技術");
        entityManager.persist(organization);
        Long organizationId = organization.getId();

        // ③ DBにSQLを反映
        entityManager.flush();

        // ④ 永続化コンテキストをクリア
        entityManager.clear();

        // ⑤ Organizationを再取得
        Organization retrievedOrganization = entityManager.find(Organization.class, organizationId);

        // ⑥ 再取得したOrganizationの検証
        assertNotNull(retrievedOrganization);

        assertNotNull(retrievedOrganization.getId());
        assertEquals(organizationId, retrievedOrganization.getId());

        assertEquals(savedUser.getId(), retrievedOrganization.getUser().getId());
        assertNull(retrievedOrganization.getParentId());

        assertNotNull(retrievedOrganization.getCreatedAt());
        assertNotNull(retrievedOrganization.getUpdatedAt());

        assertEquals("test 技術", retrievedOrganization.getName());
        assertEquals("test 技術", retrievedOrganization.getNormalizedName());
    }

    @Test // 子Organizationの保存と再取得テスト
    void testSaveAndRetrieveChildOrganization() {
        // ①Userを作成して保存
        User user = new User("child-test@example.com",
                "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②親Organizationを作成して保存
        Organization parentOrganization = new Organization(savedUser, null, "test 親技術", "test 親技術");

        entityManager.persist(parentOrganization);
        Long parentOrganizationId = parentOrganization.getId();
        entityManager.flush();
        entityManager.clear();

        // ③子Organizationを作成して保存
        Organization childOrganization = new Organization(savedUser, parentOrganizationId, "test 子技術", "test 子技術");

        entityManager.persist(childOrganization);
        Long childOrganizationId = childOrganization.getId();
        entityManager.flush();
        entityManager.clear();

        // ④ 子Organizationを再取得
        Organization retrievedChildOrganization = entityManager.find(Organization.class, childOrganizationId);

        // ⑤ 再取得した子Organizationの検証
        assertNotNull(retrievedChildOrganization);

        assertEquals(childOrganizationId, retrievedChildOrganization.getId());
        assertEquals(parentOrganizationId, retrievedChildOrganization.getParentId());

        assertEquals(savedUser.getId(), retrievedChildOrganization.getUser().getId());

        assertEquals("test 子技術", retrievedChildOrganization.getName());
        assertEquals("test 子技術", retrievedChildOrganization.getNormalizedName());

        assertNotNull(retrievedChildOrganization.getCreatedAt());
        assertNotNull(retrievedChildOrganization.getUpdatedAt());
    }

    @Test // ルートOrganizationの同名重複制約テスト
    void testDuplicateRootOrganizationNameConstraint() {
        // ①Userを作成して保存
        User user = new User("constraint-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②1件目のOrganizationを作成して保存
        Organization organization1 = new Organization(savedUser, null, "test 制約技術", "test 制約技術");
        entityManager.persist(organization1);
        entityManager.flush();

        // ③2件目のOrganizationを作成して保存
        Organization organization2 = new Organization(savedUser, null, "test 制約技術2", "test 制約技術");
        entityManager.persist(organization2);

        // ④ 一意制約違反が原因で例外がスローされることを確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.flush());
        // 根本原因を取得
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // 一意制約違反であること
        assertThat(sqlError.getSQLState())
                .isEqualTo("23505");

        // 違反した制約名を確認
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("uq_organizations_sibling_name");
    }

    @Test // 別Organizationの同名重複許可テスト
    void testAllowDuplicateRootOrganizationNameForDifferentUsers() {
        // ①UserAを作成して保存
        User userA = new User("duplicate-test-A@example.com", "test-password-hash");
        User savedUserA = userRepository.save(userA);

        // ②UserBを作成して保存
        User userB = new User("duplicate-test-B@example.com", "test-password-hash");
        User savedUserB = userRepository.save(userB);

        // ③各ユーザーのルートOrganizationを作成して保存
        Organization rootOrganizationA = new Organization(savedUserA, null, "test 重複技術", "test 重複技術");
        entityManager.persist(rootOrganizationA);
        Organization rootOrganizationB = new Organization(savedUserB, null, "test 重複技術", "test 重複技術");
        entityManager.persist(rootOrganizationB);
        entityManager.flush();
        entityManager.clear();

        // ④各ユーザーのOrganizationを再取得
        Organization retrievedRootOrganizationA = entityManager.find(Organization.class, rootOrganizationA.getId());
        Organization retrievedRootOrganizationB = entityManager.find(Organization.class, rootOrganizationB.getId());

        // ⑤再取得したOrganizationの名前が一致することを確認
        assertNotNull(retrievedRootOrganizationA);
        assertNotNull(retrievedRootOrganizationB);

        assertEquals("test 重複技術", retrievedRootOrganizationA.getName());
        assertEquals("test 重複技術", retrievedRootOrganizationB.getName());

        // ⑥ それぞれ正しいユーザーに所属していることを確認
        assertEquals(savedUserA.getId(), retrievedRootOrganizationA.getUser().getId());
        assertEquals(savedUserB.getId(), retrievedRootOrganizationB.getUser().getId());
    }

    @Test // 同一ユーザーで親が異なる子Organizationの同名重複許可テスト
    void testAllowDuplicateOrganizationNameForDifferentParents() {
        // ①Userを作成して保存
        User user = new User("different-parents-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②同一ユーザーのルートOrganizationを異なるnormalizedNameで作成して保存
        Organization parentOrganizationA = new Organization(savedUser, null, "test 親技術A", "test 親技術A");
        Organization parentOrganizationB = new Organization(savedUser, null, "test 親技術B", "test 親技術B");
        entityManager.persist(parentOrganizationA);
        entityManager.persist(parentOrganizationB);
        entityManager.flush();

        Long parentOrganizationIdA = parentOrganizationA.getId();
        Long parentOrganizationIdB = parentOrganizationB.getId();

        // ③各ルート配下に同じnormalizedNameの子Organizationを作成して保存
        Organization childOrganizationA = new Organization(savedUser, parentOrganizationIdA, "test 子技術", "test 子技術");
        Organization childOrganizationB = new Organization(savedUser, parentOrganizationIdB, "test 子技術", "test 子技術");
        entityManager.persist(childOrganizationA);
        entityManager.persist(childOrganizationB);
        entityManager.flush();

        Long childOrganizationIdA = childOrganizationA.getId();
        Long childOrganizationIdB = childOrganizationB.getId();
        entityManager.clear();

        // ④子OrganizationをDBから再取得
        Organization retrievedChildOrganizationA = entityManager.find(Organization.class, childOrganizationIdA);
        Organization retrievedChildOrganizationB = entityManager.find(Organization.class, childOrganizationIdB);

        // ⑤同じnormalizedNameの子Organizationが2件とも保存されていることを確認
        assertNotNull(retrievedChildOrganizationA);
        assertNotNull(retrievedChildOrganizationB);
        assertThat(retrievedChildOrganizationA.getId()).isNotEqualTo(retrievedChildOrganizationB.getId());
        assertEquals("test 子技術", retrievedChildOrganizationA.getNormalizedName());
        assertEquals("test 子技術", retrievedChildOrganizationB.getNormalizedName());

        // ⑥それぞれ異なる正しい親と、同じユーザーに所属していることを確認
        assertEquals(parentOrganizationIdA, retrievedChildOrganizationA.getParentId());
        assertEquals(parentOrganizationIdB, retrievedChildOrganizationB.getParentId());
        assertThat(retrievedChildOrganizationA.getParentId()).isNotEqualTo(retrievedChildOrganizationB.getParentId());
        assertEquals(savedUser.getId(), retrievedChildOrganizationA.getUser().getId());
        assertEquals(savedUser.getId(), retrievedChildOrganizationB.getUser().getId());
    }

    @Test // 同一ユーザー・同一親配下の子Organizationの同名重複制約テスト
    void testDuplicateChildOrganizationNameConstraint() {
        // ①Userを作成して保存
        User user = new User("child-constraint-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②ルートOrganizationを作成して保存
        Organization parentOrganization = new Organization(savedUser, null, "test 親技術", "test 親技術");
        entityManager.persist(parentOrganization);
        entityManager.flush();
        Long parentOrganizationId = parentOrganization.getId();

        // ③1件目の子Organizationを正常保存
        Organization childOrganization1 = new Organization(savedUser, parentOrganizationId, "test 子技術", "test 子技術");
        entityManager.persist(childOrganization1);
        entityManager.flush();

        // ④同じ親ID・normalizedNameを持つ2件目の子Organizationを作成
        Organization childOrganization2 = new Organization(savedUser, parentOrganizationId, "test 子技術2", "test 子技術");
        entityManager.persist(childOrganization2);

        // ⑤2件目のflushで一意制約違反が発生することを確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.flush());
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // ⑥一意制約違反のSQLSTATEと制約名を確認
        assertThat(sqlError.getSQLState())
                .isEqualTo("23505");
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();
        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("uq_organizations_sibling_name");
    }

    @Test // 異なるユーザーが所有する親Organizationの指定を拒否するテスト
    void testRejectParentOrganizationOwnedByDifferentUser() {
        // ①User AとUser Bを作成して保存
        User userA = new User("parent-owner-A@example.com", "test-password-hash");
        User savedUserA = userRepository.save(userA);
        User userB = new User("parent-owner-B@example.com", "test-password-hash");
        User savedUserB = userRepository.save(userB);

        // ②User Aが所有するルートOrganizationを作成して保存
        Organization parentOrganization = new Organization(savedUserA, null, "test 親技術", "test 親技術");
        entityManager.persist(parentOrganization);
        entityManager.flush();

        // ③User Bの子OrganizationにUser AのルートOrganizationを親として指定
        Organization childOrganization = new Organization(savedUserB, parentOrganization.getId(), "test 子技術",
                "test 子技術");
        entityManager.persist(childOrganization);

        // ④flushで複合外部キー制約違反が発生することを確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.flush());
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // ⑤外部キー制約違反のSQLSTATEと親子所有者整合性の制約名を確認
        assertThat(sqlError.getSQLState())
                .isEqualTo("23503");
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();
        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("fk_organizations_parent_owner");
    }

    @Test // 存在しない親Organizationの指定を拒否するテスト
    void testRejectNonExistentParentOrganization() {
        // ①Userを作成して保存
        User user = new User("nonexistent-parent-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);
        entityManager.flush();

        // ②存在しない親IDを指定した子Organizationを作成
        Long nonExistentParentId = Long.MAX_VALUE;
        Organization childOrganization = new Organization(savedUser, nonExistentParentId, "test 子技術", "test 子技術");
        entityManager.persist(childOrganization);

        // ③flushで外部キー制約違反が発生することを確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.flush());
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // ④外部キー制約違反のSQLSTATEと制約名を確認
        assertThat(sqlError.getSQLState())
                .isEqualTo("23503");
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();
        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("fk_organizations_parent_owner");
    }

    @Test // Organizationの親に自分自身を指定する更新を拒否するテスト
    void testRejectOrganizationSelfParentReference() {
        // ①Userを作成して保存
        User user = new User("self-parent-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②ルートOrganizationを正常保存し、採番されたIDを取得
        Organization organization = new Organization(savedUser, null, "test 自己参照技術", "test 自己参照技術");
        entityManager.persist(organization);
        entityManager.flush();
        Long organizationId = organization.getId();
        assertNotNull(organizationId);

        // ③対象Organizationのparent_idを自身のidに更新し、CHECK制約違反を確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.createNativeQuery(
                        "UPDATE organizations SET parent_id = :organizationId WHERE id = :organizationId")
                        .setParameter("organizationId", organizationId)
                        .executeUpdate());
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // ④CHECK制約違反のSQLSTATEと制約名を確認（違反後のDB操作は行わない）
        assertThat(sqlError.getSQLState())
                .isEqualTo("23514");
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();
        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("chk_organizations_parent_not_self");
    }

    @Test // OrganizationのnameをNULLに更新するとNOT NULL制約で拒否されるテスト
    void testRejectNullOrganizationName() {
        // ①Userを作成して保存
        User user = new User("null-organization-name-test@example.com", "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②正常なルートOrganizationを保存し、採番されたIDを取得
        Organization organization = new Organization(savedUser, null, "test 技術", "test 技術");
        entityManager.persist(organization);
        entityManager.flush();
        Long organizationId = organization.getId();
        assertNotNull(organizationId);

        // ③対象OrganizationのnameをNULLに更新し、NOT NULL制約違反を確認
        PersistenceException error = assertThrows(
                PersistenceException.class,
                () -> entityManager.createNativeQuery(
                        "UPDATE organizations SET name = NULL WHERE id = :organizationId")
                        .setParameter("organizationId", organizationId)
                        .executeUpdate());
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;

        // ④NOT NULL制約違反のSQLSTATEと違反対象カラムを確認
        assertThat(sqlError.getSQLState())
                .isEqualTo("23502");
        assertThat(sqlError.getServerErrorMessage())
                .isNotNull();
        assertThat(sqlError.getServerErrorMessage().getColumn())
                .isEqualTo("name");
    }

    @ParameterizedTest // name以外の必須カラムのNOT NULL制約をDBで確認
    @ValueSource(strings = { "normalized_name", "user_id", "id", "created_at", "updated_at" })
    void testRejectNullOrganizationRequiredColumn(String column) {
        Organization organization = saveRootOrganizationForConstraintTest();

        // SQL識別子には、このValueSourceの固定値のみを使用する。
        PSQLException sqlError = assertPostgresError("23502",
                () -> entityManager.createNativeQuery(
                        "UPDATE organizations SET " + column + " = NULL WHERE id = :organizationId")
                        .setParameter("organizationId", organization.getId())
                        .executeUpdate());

        assertThat(sqlError.getServerErrorMessage().getColumn()).isEqualTo(column);
    }

    @ParameterizedTest // 空文字・半角空白・タブ・改行のみのnameを拒否
    @ValueSource(strings = { "", " ", "   ", "\t", "\n\n", " \n\t " })
    void testRejectBlankOrganizationName(String name) {
        User user = saveUserForOrganizationConstraintTest();
        Organization organization = new Organization(user, null, name, "test 技術");
        entityManager.persist(organization);

        PSQLException sqlError = assertPostgresError("23514", entityManager::flush);

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("chk_organizations_name_nonblank");
    }

    @ParameterizedTest // 空文字・半角空白・タブ・改行のみのnormalized_nameを拒否
    @ValueSource(strings = { "", " ", "   ", "\t", "\n\n", " \n\t " })
    void testRejectBlankOrganizationNormalizedName(String normalizedName) {
        User user = saveUserForOrganizationConstraintTest();
        Organization organization = new Organization(user, null, "test 技術", normalizedName);
        entityManager.persist(organization);

        PSQLException sqlError = assertPostgresError("23514", entityManager::flush);

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("chk_organizations_normalized_name_nonblank");
    }

    @Test // 存在しないUserを所有者に指定した更新を拒否
    void testRejectNonExistentOrganizationUser() {
        Organization organization = saveRootOrganizationForConstraintTest();
        assertNull(entityManager.find(User.class, Long.MAX_VALUE));

        // parent_idはNULLのままとし、所有ユーザーのFKだけを検証する。
        PSQLException sqlError = assertPostgresError("23503",
                () -> entityManager.createNativeQuery(
                        "UPDATE organizations SET user_id = :userId WHERE id = :organizationId")
                        .setParameter("userId", Long.MAX_VALUE)
                        .setParameter("organizationId", organization.getId())
                        .executeUpdate());

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("fk_organizations_user");
    }

    @Test // 子が残る親Organizationの削除はRESTRICTで拒否
    void testRejectDeletionOfParentOrganizationWithChildren() {
        Organization parent = saveRootOrganizationForConstraintTest();
        Organization child = new Organization(parent.getUser(), parent.getId(), "test 子技術", "test 子技術");
        entityManager.persist(child);
        entityManager.flush();

        entityManager.remove(parent);
        PSQLException sqlError = assertPostgresError("23503", entityManager::flush);

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("fk_organizations_parent_owner");
    }

    @Test // Organizationが残るUserの削除はRESTRICTで拒否
    void testRejectDeletionOfUserWithOrganizations() {
        Organization organization = saveRootOrganizationForConstraintTest();

        // remove(User)はHibernateの関連検査で先に失敗するため、DBのRESTRICTを直接検証する。
        PSQLException sqlError = assertPostgresError("23503",
                () -> entityManager.createNativeQuery("DELETE FROM users WHERE id = :userId")
                        .setParameter("userId", organization.getUser().getId())
                        .executeUpdate());

        assertThat(sqlError.getServerErrorMessage().getConstraint())
                .isEqualTo("fk_organizations_user");
    }

    @ParameterizedTest // 文字数上限255はASCII・日本語ともに保存できる
    @ValueSource(strings = { "a", "技" })
    void testAllowOrganizationNamesAtMaximumLength(String character) {
        User user = saveUserForOrganizationConstraintTest();
        String maximumLengthName = character.repeat(255);
        Organization organization = new Organization(user, null, maximumLengthName, maximumLengthName);
        entityManager.persist(organization);
        entityManager.flush();
        Long organizationId = organization.getId();
        entityManager.clear();

        Organization retrievedOrganization = entityManager.find(Organization.class, organizationId);

        assertNotNull(retrievedOrganization);
        assertThat(retrievedOrganization.getName()).isEqualTo(maximumLengthName).hasSize(255);
        assertThat(retrievedOrganization.getNormalizedName()).isEqualTo(maximumLengthName).hasSize(255);
        assertEquals(user.getId(), retrievedOrganization.getUser().getId());
    }

    @ParameterizedTest // 各カラムを単独で256文字にし、VARCHAR(255)の超過を確認
    @CsvSource({ "name,a", "normalized_name,a", "name,技", "normalized_name,技" })
    void testRejectOrganizationNamesExceedingMaximumLength(String column, String character) {
        User user = saveUserForOrganizationConstraintTest();
        String tooLongName = character.repeat(256);
        String name = column.equals("name") ? tooLongName : "test 技術";
        String normalizedName = column.equals("normalized_name") ? tooLongName : "test 技術";
        Organization organization = new Organization(user, null, name, normalizedName);
        entityManager.persist(organization);

        // 文字列長超過は22001。PostgreSQLはこのエラーにカラム名・独自制約名を返さない。
        assertPostgresError("22001", entityManager::flush);
    }

    @Test // 異なるUserの行同士でIDを重複させ、主キー制約だけに違反させる
    void testDuplicateOrganizationIdConstraint() {
        Organization first = saveRootOrganizationForConstraintTest();
        User otherUser = userRepository.save(new User("organization-other@example.test", "test-password-hash"));
        Organization second = new Organization(otherUser, null, "test 別技術", "test 別技術");
        entityManager.persist(second);
        entityManager.flush();

        PSQLException sqlError = assertPostgresError("23505",
                () -> entityManager.createNativeQuery(
                        "UPDATE organizations SET id = :duplicateId WHERE id = :organizationId")
                        .setParameter("duplicateId", first.getId())
                        .setParameter("organizationId", second.getId())
                        .executeUpdate());

        assertThat(sqlError.getServerErrorMessage().getConstraint()).isEqualTo("pk_organizations");
    }

    @Test // UNIQUE(id, user_id)はPKと重なるため、複合FKの参照先キー定義を実DBで確認
    void testOrganizationIdUserUniqueConstraintDefinition() {
        Object definition = entityManager.createNativeQuery("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = CAST('organizations' AS regclass)
                  AND conname = :constraintName
                  AND contype = 'u'
                """)
                .setParameter("constraintName", "uq_organizations_id_user")
                .getSingleResult();

        assertThat(definition).isEqualTo("UNIQUE (id, user_id)");
    }

    @ParameterizedTest // 非空白文字を含む値は前後に空白があってもDBのCHECKを満たす
    @ValueSource(strings = { " 技術 ", "\t技術\n" })
    void testAllowOrganizationNamesContainingNonblankCharacter(String name) {
        User user = saveUserForOrganizationConstraintTest();
        Organization organization = new Organization(user, null, name, name);
        entityManager.persist(organization);
        entityManager.flush();
        Long organizationId = organization.getId();
        entityManager.clear();

        Organization retrievedOrganization = entityManager.find(Organization.class, organizationId);

        assertNotNull(retrievedOrganization);
        // trim・小文字化はServiceの責務。ここではDBが保存値を保持することを確認する。
        assertEquals(name, retrievedOrganization.getName());
        assertEquals(name, retrievedOrganization.getNormalizedName());
    }

    @Test // 参照がないOrganizationを削除すれば、所有Userも削除できる
    void testDeleteLeafOrganizationAndThenUnreferencedUser() {
        Organization organization = saveRootOrganizationForConstraintTest();
        Long organizationId = organization.getId();
        User user = organization.getUser();
        Long userId = user.getId();

        entityManager.remove(organization);
        entityManager.flush();
        entityManager.remove(user);
        entityManager.flush();
        entityManager.clear();

        assertNull(entityManager.find(Organization.class, organizationId));
        assertNull(entityManager.find(User.class, userId));
    }

    private User saveUserForOrganizationConstraintTest() {
        return userRepository.save(new User("organization-constraint@example.test", "test-password-hash"));
    }

    private Organization saveRootOrganizationForConstraintTest() {
        User user = saveUserForOrganizationConstraintTest();
        Organization organization = new Organization(user, null, "test 技術", "test 技術");
        entityManager.persist(organization);
        entityManager.flush();
        return organization;
    }

    private PSQLException assertPostgresError(String sqlState, Executable operation) {
        // 各異常系・パラメータ化ケースは独立した@DataJpaTestのトランザクションで実行する。
        PersistenceException error = assertThrows(PersistenceException.class, operation);
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertThat(cause).isInstanceOf(PSQLException.class);
        PSQLException sqlError = (PSQLException) cause;
        assertThat(sqlError.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlError.getServerErrorMessage()).isNotNull();
        // 制約違反後は例外情報だけを検証し、同じトランザクションでDBへアクセスしない。
        return sqlError;
    }
}
