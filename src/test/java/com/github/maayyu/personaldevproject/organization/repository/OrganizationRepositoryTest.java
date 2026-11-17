package com.github.maayyu.personaldevproject.organization.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
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

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
public class OrganizationRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test // 所有者が一致するOrganizationを取得できるテスト
    public void testFindByIdAndUserId() {
        // ①Userを作成して保存
        User user = new User("memo-test@example.com",
                "test-password-hash");
        User savedUser = userRepository.save(user);

        // ②Organizationを作成して保存
        Organization organization = new Organization(savedUser, null, "test 技術", "test 技術");
        organizationRepository.save(organization);
        Long organizationId = organization.getId();

        // ③ DBにSQLを反映
        organizationRepository.flush();

        // ④ 永続化コンテキストをクリア
        entityManager.clear();

        // ⑤ Organizationを再取得
        Organization retrievedOrganization = organizationRepository.findByIdAndUserId(organizationId, savedUser.getId())
                .orElse(null);

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

    @Test // 他ユーザーが所有するOrganizationを取得できない
    public void testFindByIdAndUserIdWithDifferentOwner() {
        User userA = userRepository.save(
                new User("owner-a@example.com", "test-password-hash"));
        User userB = userRepository.save(
                new User("owner-b@example.com", "test-password-hash"));

        Organization organization = organizationRepository.save(
                new Organization(userA, null, "test 技術", "test 技術"));
        Long organizationId = organization.getId();
        Long userBId = userB.getId();

        organizationRepository.flush();
        entityManager.clear();

        assertTrue(organizationRepository
                .findByIdAndUserId(organizationId, userBId)
                .isEmpty());
    }

    @Test // 正しい所有者でも存在しないOrganizationを取得できない
    public void testFindByIdAndUserIdWithNonexistentOrganizationId() {
        User user = userRepository.save(
                new User("missing-org@example.com", "test-password-hash"));
        organizationRepository.save(
                new Organization(user, null, "test 技術", "test 技術"));
        Long userId = user.getId();

        organizationRepository.flush();
        entityManager.clear();

        // V1の採番は1から開始するため、0は採番されない
        Long nonexistentOrganizationId = 0L;

        assertTrue(organizationRepository
                .findByIdAndUserId(nonexistentOrganizationId, userId)
                .isEmpty());
    }

    @Test // ルート・子階層を含む自分の一覧を、名前ASC・同名時ID ASCで取得する
    public void testFindAllByUserIdOrderByNameAscIdAsc() {
        User user = saveUser("list-owner@example.com");
        User otherUser = saveUser("list-other@example.com");
        Organization zuluParent = saveOrganization(user, null, "Zulu", "zulu");
        Organization bravoParent = saveOrganization(user, null, "Bravo", "bravo");
        // 同名は別の親配下に置き、同階層のnormalized_name一意制約を守る。
        Organization firstAlpha = saveOrganization(user, zuluParent.getId(), "Alpha", "alpha");
        Organization secondAlpha = saveOrganization(user, bravoParent.getId(), "Alpha", "alpha");
        saveOrganization(otherUser, null, "Alpha", "alpha");

        flushAndClear();

        List<Organization> organizations = organizationRepository
                .findAllByUserIdOrderByNameAscIdAsc(user.getId());

        assertEquals(List.of(firstAlpha.getId(), secondAlpha.getId(), bravoParent.getId(), zuluParent.getId()),
                organizations.stream().map(Organization::getId).toList());
    }

    @Test // 他ユーザーにデータがあっても、自分にデータがなければ空一覧になる
    public void testFindAllByUserIdOrderByNameAscIdAscWithNoOwnedOrganizations() {
        User user = saveUser("empty-list-owner@example.com");
        User otherUser = saveUser("empty-list-other@example.com");
        saveOrganization(otherUser, null, "Alpha", "alpha");

        flushAndClear();

        assertTrue(organizationRepository.findAllByUserIdOrderByNameAscIdAsc(user.getId()).isEmpty());
    }

    @Test // 所有者と親が一致する直接の子があればtrueになる
    public void testExistsByParentIdAndUserId() {
        User user = saveUser("exists-owner@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        saveOrganization(user, parent.getId(), "Child", "child");

        flushAndClear();

        assertTrue(organizationRepository.existsByParentIdAndUserId(parent.getId(), user.getId()));
    }

    @Test // 子なし・存在しない親・所有者不一致ではfalseになる
    public void testExistsByParentIdAndUserIdWithNoMatchingChild() {
        User user = saveUser("missing-child-owner@example.com");
        User otherUser = saveUser("missing-child-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        Organization child = saveOrganization(user, parent.getId(), "Child", "child");
        Organization emptyParent = saveOrganization(user, null, "Empty", "empty");

        flushAndClear();

        assertFalse(organizationRepository.existsByParentIdAndUserId(child.getId(), user.getId()));
        assertFalse(organizationRepository.existsByParentIdAndUserId(emptyParent.getId(), user.getId()));
        // V1のSEQUENCEで採番されない0を使用する。
        assertFalse(organizationRepository.existsByParentIdAndUserId(0L, user.getId()));
        assertFalse(organizationRepository.existsByParentIdAndUserId(parent.getId(), otherUser.getId()));
    }

    @Test // 指定親の直接の子だけを名前ASCで取得し、ルート・別親・孫・他所有者を除外する
    public void testFindAllByParentIdAndUserIdOrderByNameAscIdAsc() {
        User user = saveUser("children-owner@example.com");
        User otherUser = saveUser("children-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        Organization otherParent = saveOrganization(user, null, "Other", "other");
        Organization zulu = saveOrganization(user, parent.getId(), "Zulu", "zulu");
        Organization alpha = saveOrganization(user, parent.getId(), "Alpha", "alpha");
        saveOrganization(user, otherParent.getId(), "Alpha", "alpha");
        saveOrganization(user, zulu.getId(), "Alpha", "alpha");
        Organization foreignParent = saveOrganization(otherUser, null, "Parent", "parent");
        saveOrganization(otherUser, foreignParent.getId(), "Alpha", "alpha");

        flushAndClear();

        List<Organization> children = organizationRepository
                .findAllByParentIdAndUserIdOrderByNameAscIdAsc(parent.getId(), user.getId());

        assertEquals(List.of(alpha.getId(), zulu.getId()),
                children.stream().map(Organization::getId).toList());
    }

    @Test // 子がない親・存在しない親・他所有者の親では空一覧になる
    public void testFindAllByParentIdAndUserIdOrderByNameAscIdAscWithNoMatchingChildren() {
        User user = saveUser("empty-children-owner@example.com");
        User otherUser = saveUser("empty-children-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        Organization child = saveOrganization(user, parent.getId(), "Child", "child");
        Organization emptyParent = saveOrganization(user, null, "Empty", "empty");

        flushAndClear();

        assertTrue(organizationRepository
                .findAllByParentIdAndUserIdOrderByNameAscIdAsc(child.getId(), user.getId()).isEmpty());
        assertTrue(organizationRepository
                .findAllByParentIdAndUserIdOrderByNameAscIdAsc(emptyParent.getId(), user.getId()).isEmpty());
        assertTrue(organizationRepository
                .findAllByParentIdAndUserIdOrderByNameAscIdAsc(0L, user.getId()).isEmpty());
        assertTrue(organizationRepository
                .findAllByParentIdAndUserIdOrderByNameAscIdAsc(parent.getId(), otherUser.getId()).isEmpty());
    }

    @Test // 同じ正規化名がルート・別親・他所有者にもあっても、指定親配下の1件を取得する
    public void testFindByUserIdAndParentIdAndNormalizedName() {
        User user = saveUser("normalized-child-owner@example.com");
        User otherUser = saveUser("normalized-child-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        Organization otherParent = saveOrganization(user, null, "Other", "other");
        saveOrganization(user, null, "Tech", "tech");
        saveOrganization(user, otherParent.getId(), "Tech", "tech");
        Organization target = saveOrganization(user, parent.getId(), "Tech", "tech");
        Organization foreignParent = saveOrganization(otherUser, null, "Parent", "parent");
        saveOrganization(otherUser, foreignParent.getId(), "Tech", "tech");

        flushAndClear();

        Organization found = organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(user.getId(), parent.getId(), "tech")
                .orElseThrow();

        assertEquals(target.getId(), found.getId());
        assertEquals("Tech", found.getName());
        assertEquals("tech", found.getNormalizedName());
    }

    @Test // 正規化名・親・所有者の不一致、およびルートだけの一致では取得しない
    public void testFindByUserIdAndParentIdAndNormalizedNameWithNoMatch() {
        User user = saveUser("missing-normalized-child-owner@example.com");
        User otherUser = saveUser("missing-normalized-child-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        Organization otherParent = saveOrganization(user, null, "Other", "other");
        saveOrganization(user, parent.getId(), "Tech", "tech");
        saveOrganization(user, null, "RootOnly", "rootonly");

        flushAndClear();

        assertTrue(organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(user.getId(), parent.getId(), "missing").isEmpty());
        // Repositoryは表示名ではなく、渡されたnormalizedNameの一致を検索する。
        assertTrue(organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(user.getId(), parent.getId(), "Tech").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(user.getId(), otherParent.getId(), "tech").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(user.getId(), parent.getId(), "rootonly").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdAndNormalizedName(otherUser.getId(), parent.getId(), "tech").isEmpty());
    }

    @Test // 子階層や他所有者にも同じ正規化名があっても、自分のルートだけを取得する
    public void testFindByUserIdAndParentIdIsNullAndNormalizedName() {
        User user = saveUser("normalized-root-owner@example.com");
        User otherUser = saveUser("normalized-root-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        saveOrganization(user, parent.getId(), "Tech", "tech");
        saveOrganization(otherUser, null, "Tech", "tech");
        Organization target = saveOrganization(user, null, "Tech", "tech");

        flushAndClear();

        Organization found = organizationRepository
                .findByUserIdAndParentIdIsNullAndNormalizedName(user.getId(), "tech")
                .orElseThrow();

        assertEquals(target.getId(), found.getId());
        assertNull(found.getParentId());
        assertEquals("Tech", found.getName());
        assertEquals("tech", found.getNormalizedName());
    }

    @Test // 名前不一致・子階層のみ・他所有者のみの一致ではルートを取得しない
    public void testFindByUserIdAndParentIdIsNullAndNormalizedNameWithNoMatch() {
        User user = saveUser("missing-normalized-root-owner@example.com");
        User otherUser = saveUser("missing-normalized-root-other@example.com");
        Organization parent = saveOrganization(user, null, "Parent", "parent");
        saveOrganization(user, parent.getId(), "ChildOnly", "childonly");
        saveOrganization(otherUser, null, "ForeignOnly", "foreignonly");

        flushAndClear();

        assertTrue(organizationRepository
                .findByUserIdAndParentIdIsNullAndNormalizedName(user.getId(), "missing").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdIsNullAndNormalizedName(user.getId(), "Parent").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdIsNullAndNormalizedName(user.getId(), "childonly").isEmpty());
        assertTrue(organizationRepository
                .findByUserIdAndParentIdIsNullAndNormalizedName(user.getId(), "foreignonly").isEmpty());
    }

    private User saveUser(String email) {
        return userRepository.save(new User(email, "test-password-hash"));
    }

    private Organization saveOrganization(User user, Long parentId, String name, String normalizedName) {
        return organizationRepository.save(new Organization(user, parentId, name, normalizedName));
    }

    private void flushAndClear() {
        organizationRepository.flush();
        entityManager.clear();
    }
}
