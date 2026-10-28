package com.github.maayyu.personaldevproject.memo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.github.maayyu.personaldevproject.PostgresTestConfiguration;
import com.github.maayyu.personaldevproject.memo.entity.Memo;
import com.github.maayyu.personaldevproject.user.entity.User;
import com.github.maayyu.personaldevproject.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
public class MemoRepositoryTest {

        @Autowired
        private MemoRepository memoRepository;

        @Autowired
        private EntityManager entityManager;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test // Memoの保存と再取得のテスト
        void testSaveAndFindMemo() {
                // ① Userを作成して保存
                User user = new User(
                                "memo-test@example.com",
                                "test-password-hash");
                User savedUser = userRepository.save(user);

                // ② Memoを作成して保存
                Memo memo = new Memo(
                                savedUser,
                                "Test Java学習",
                                "JPAのRepositoryテスト");
                Memo savedMemo = memoRepository.save(memo);

                // ③ DBにSQLを反映
                entityManager.flush();

                // ④ 永続化コンテキストをクリア
                entityManager.clear();

                // ⑤ DBからMemoを再取得
                Memo reloadedMemo = memoRepository.findById(savedMemo.getId()).orElseThrow();

                // ⑥ 保存内容を検証
                assertThat(reloadedMemo)
                                .isNotSameAs(savedMemo);

                assertThat(reloadedMemo.getId())
                                .isEqualTo(savedMemo.getId());

                assertThat(reloadedMemo.getTitle())
                                .isEqualTo("Test Java学習");

                assertThat(reloadedMemo.getContent())
                                .isEqualTo("JPAのRepositoryテスト");

                assertThat(reloadedMemo.getUser().getId())
                                .isEqualTo(savedUser.getId());
        }

        @Test // 255文字を超えるMemo本文の保存・再取得テスト
        void testSaveAndFindLongContentMemo() {
                // ① Userを作成して保存
                User user = userRepository.save(
                                new User("memo-long-content@example.com", "password-hash"));

                // ② 300文字の本文を作成
                String longContent = "A".repeat(300);

                // ③ 300文字の本文を持つMemoを保存
                Memo savedMemo = memoRepository.save(
                                new Memo(user, "長文保存テスト", longContent));

                Long memoId = savedMemo.getId();

                // ④ DBにINSERTを反映
                entityManager.flush();

                // ⑤ 永続化コンテキストをクリア
                entityManager.clear();

                // ⑥ DBから再取得
                Memo reloadedMemo = memoRepository.findById(memoId)
                                .orElseThrow();

                // ⑦ 保存した本文と完全一致することを確認
                assertThat(reloadedMemo.getContent())
                                .isEqualTo(longContent);

                // ⑧ 300文字が保持されていることを確認
                assertThat(reloadedMemo.getContent())
                                .hasSize(300);
        }

        @Test // 所有者による検索制御テスト
        void testFindByIdAndUserId() {
                // ① 2つのUserを作成して保存
                User user1 = userRepository.save(new User("user1@example.com", "password1"));
                User user2 = userRepository.save(new User("user2@example.com", "password2"));

                // ② User1が所有するMemoを作成して保存
                Memo memo1 = memoRepository.save(new Memo(user1, "User1 所有者検索テスト", "Content for User1's memo Memoの内容"));

                Long memoId = memo1.getId();
                Long user1Id = user1.getId();
                Long user2Id = user2.getId();

                // ③ DBへ反映し、永続化コンテキストをクリアする
                entityManager.flush();
                entityManager.clear();

                // ④ 所有者自身で検索：取得できる
                var foundMemoByOwner = memoRepository.findByIdAndUser_Id(memoId, user1Id);
                assertThat(foundMemoByOwner).isPresent();
                assertThat(foundMemoByOwner.get().getId()).isEqualTo(memoId);

                // ⑤ 他のユーザーで検索：取得できない
                var foundMemoByOtherUser = memoRepository.findByIdAndUser_Id(memoId, user2Id);
                assertThat(foundMemoByOtherUser).isNotPresent();

                // ⑥ 存在しないIDで検索：取得できない
                var foundMemoByNonExistentId = memoRepository.findByIdAndUser_Id(Long.MAX_VALUE, user1Id);
                assertThat(foundMemoByNonExistentId).isNotPresent();

        }

        @Test // Memo IDとデータベース生成タイムスタンプテスト
        void testMemoIdAndDatabaseGeneratedTimestamps() {
                // ① Userを作成し、先にDBへ保存
                User user = userRepository.save(
                                new User("memo-timestamps@example.com", "test-password-hash"));
                entityManager.flush();

                // ② Memo INSERT前のDB時刻を取得
                Instant beforeInsert = databaseClock();

                // ③ Memoを2件作成して保存
                Memo memo1 = memoRepository.save(
                                new Memo(user, "メモ1", "内容1"));

                Memo memo2 = memoRepository.save(
                                new Memo(user, "メモ2", "内容2"));

                Long memoId1 = memo1.getId();
                Long memoId2 = memo2.getId();

                // ④ DBにINSERTを反映
                entityManager.flush();

                // ⑤ Memo INSERT後のDB時刻を取得
                Instant afterInsert = databaseClock();

                // ⑥ clear前のEntityから日時を取得
                Instant createdAt = memo1.getCreatedAt();
                Instant updatedAt = memo1.getUpdatedAt();
                Instant contentUpdatedAt = memo1.getContentUpdatedAt();

                // ⑦ IDが採番されていることを検証
                assertThat(memoId1).isNotNull().isPositive();
                assertThat(memoId2).isNotNull().isPositive();
                assertThat(memoId1).isNotEqualTo(memoId2);

                // ⑧ INSERT直後のEntityに日時が反映されていることを検証
                assertThat(createdAt)
                                .isNotNull()
                                .isBetween(beforeInsert, afterInsert);

                assertThat(updatedAt)
                                .isNotNull()
                                .isBetween(beforeInsert, afterInsert);

                assertThat(contentUpdatedAt)
                                .isNotNull()
                                .isBetween(beforeInsert, afterInsert);

                // ⑨ PostgreSQLのマイクロ秒精度を検証
                assertThat(createdAt.getNano() % 1_000).isZero();
                assertThat(updatedAt.getNano() % 1_000).isZero();
                assertThat(contentUpdatedAt.getNano() % 1_000).isZero();

                // ⑩ DB保存値とEntityの日時が一致することを検証
                Instant dbCreatedAt = jdbcTemplate.queryForObject(
                                "SELECT created_at FROM memos WHERE id = ?",
                                OffsetDateTime.class, memoId1).toInstant();

                Instant dbUpdatedAt = jdbcTemplate.queryForObject(
                                "SELECT updated_at FROM memos WHERE id = ?",
                                OffsetDateTime.class, memoId1).toInstant();

                Instant dbContentUpdatedAt = jdbcTemplate.queryForObject(
                                "SELECT content_updated_at FROM memos WHERE id = ?",
                                OffsetDateTime.class, memoId1).toInstant();

                assertThat(createdAt).isEqualTo(dbCreatedAt);
                assertThat(updatedAt).isEqualTo(dbUpdatedAt);
                assertThat(contentUpdatedAt).isEqualTo(dbContentUpdatedAt);

                // ⑪ 永続化コンテキストをクリア
                entityManager.clear();

                // ⑫ DBから再取得
                Memo reloadedMemo = memoRepository.findById(memoId1).orElseThrow();

                // ⑬ 再取得後も同じ日時であることを検証
                assertThat(reloadedMemo.getCreatedAt()).isEqualTo(createdAt);
                assertThat(reloadedMemo.getUpdatedAt()).isEqualTo(updatedAt);
                assertThat(reloadedMemo.getContentUpdatedAt())
                                .isEqualTo(contentUpdatedAt);
        }

        @Test // Memo IDのSEQUENCE採番とINSERT前採番のテスト
        void testMemoIdSequenceAllocationBeforeInsert() {
                // ① Userを作成し、先にDBへ保存
                User user = userRepository.save(
                                new User("memo-sequence@example.com", "password-hash"));
                entityManager.flush();

                // ② 1件目のMemoを保存
                Memo memo1 = memoRepository.save(
                                new Memo(user, "メモ1", "内容1"));

                // ③ INSERT前にIDが採番されていることを確認
                assertThat(memo1.getId()).isNotNull().isPositive();

                // ④ memos_id_seqから採番されたことを確認
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT currval('memos_id_seq')", Long.class))
                                .isEqualTo(memo1.getId());

                // ⑤ flush前なのでDBにはまだMemo行がない
                assertThat(countMemoRows(memo1.getId())).isZero();

                // ⑥ 2件目のMemoを保存
                Memo memo2 = memoRepository.save(
                                new Memo(user, "メモ2", "内容2"));

                // ⑦ IDが1増えていることを確認
                assertThat(memo2.getId())
                                .isEqualTo(memo1.getId() + 1);

                // ⑧ 2件目もmemos_id_seqから採番されたことを確認
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT currval('memos_id_seq')", Long.class))
                                .isEqualTo(memo2.getId());

                // ⑨ まだINSERT前であることを確認
                assertThat(countMemoRows(memo1.getId())).isZero();
                assertThat(countMemoRows(memo2.getId())).isZero();

                // ⑩ DBにINSERTを反映
                entityManager.flush();

                // ⑪ DBに両方のMemoが保存されたことを確認
                assertThat(countMemoRows(memo1.getId())).isEqualTo(1L);
                assertThat(countMemoRows(memo2.getId())).isEqualTo(1L);
        }

        @Test // Memoのtitleがnullの場合、DBで拒否されることをテスト
        void testMemoTitleNotNullConstraint() {
                User user = userRepository.save(
                                new User("memo-not-null@example.com", "password-hash"));

                entityManager.flush();

                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                user.getId(), null, "本文は正常"))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        // エラーの根本原因がPostgreSQL例外であることを確認
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // NOT NULL制約違反であることを確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23502");

                                        // 違反したカラムがtitleであることを確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getColumn())
                                                        .isEqualTo("title");
                                });
        }

        @Test // Memoのuser_idが存在しない場合、DBで拒否されることをテスト
        void testMemoUserForeignKeyConstraint() {
                // ① 存在しないUser IDでINSERT
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                Long.MAX_VALUE, "外部キーテスト", "本文は正常"))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        // ② PostgreSQLの例外であることを確認
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // ③ 外部キー違反（SQLSTATE 23503）を確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23503");

                                        // ④ 違反した制約名を確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getConstraint())
                                                        .isEqualTo("fk_memos_user");
                                });
        }

        @Test // Memoのcontentがnullの場合、DBで拒否されることをテスト
        void testMemoContentNotNullConstraint() {
                // ① Userを作成・保存
                User user = userRepository.save(
                                new User("memo-content-null@example.com", "password-hash"));

                entityManager.flush();

                // ② contentがnullの場合、DBがINSERTを拒否する
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                user.getId(), "NOT NULLテスト", null))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // NOT NULL違反であること
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23502");

                                        // 違反したカラムがcontentであること
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getColumn())
                                                        .isEqualTo("content");
                                });
        }

        @Test // Memoのcontentが空白のみの場合、CHECK制約で拒否されることをテスト
        void testMemoContentNonblankCheckConstraint() {
                // ① Userを作成・保存
                User user = userRepository.save(
                                new User("memo-content-blank@example.com", "password-hash"));

                entityManager.flush();

                // ② 空白のみのcontentでINSERTを試みる
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                user.getId(), "CHECK制約テスト", "   "))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        // ③ PostgreSQLが発生させた例外かを確認
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // ④ CHECK制約違反であることを確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23514");

                                        // ⑤ 違反した制約名を確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getConstraint())
                                                        .isEqualTo("chk_memos_content_nonblank");
                                });
        }

        @Test // Memoのcontentが空文字の場合、CHECK制約で拒否されることをテスト
        void testMemoContentEmptyCheckConstraint() {
                // ① Userを作成・保存
                User user = userRepository.save(
                                new User("memo-content-empty@example.com", "password-hash"));

                entityManager.flush();

                // ② contentに空文字を指定してINSERTを試みる
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                user.getId(), "空文字テスト", ""))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        // ③ PostgreSQLの例外か確認
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // ④ CHECK制約違反であることを確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23514");

                                        // ⑤ 違反した制約名を確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getConstraint())
                                                        .isEqualTo("chk_memos_content_nonblank");
                                });
        }

        @ParameterizedTest
        @ValueSource(strings = { "\n\n", "\t", " \n\t " })
        void testMemoContentWhitespaceOnlyCheckConstraint(String invalidContent) {
                // ① Userを作成し、先にDBへ保存
                User user = userRepository.save(
                                new User("memo-whitespace@example.com", "password-hash"));

                entityManager.flush();

                // ② 空白文字のみの本文でINSERTを試みる
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (?, ?, ?)",
                                user.getId(), "空白文字テスト", invalidContent))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // CHECK制約違反であることを確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23514");

                                        // 本文の非空白CHECK制約であることを確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getConstraint())
                                                        .isEqualTo("chk_memos_content_nonblank");
                                });
        }

        @Test // Memoのuser_idがnullの場合、DBで拒否されることをテスト
        void testMemoUserIdNotNullConstraint() {
                // ① user_idにNULLを指定してINSERTを試みる
                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO memos (user_id, title, content) VALUES (NULL, ?, ?)",
                                "所有者未指定テスト", "本文あり"))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(error -> {
                                        // ② PostgreSQLの例外であることを確認
                                        assertThat(error.getCause())
                                                        .isInstanceOf(PSQLException.class);

                                        PSQLException sqlError = (PSQLException) error.getCause();

                                        // ③ NOT NULL違反であることを確認
                                        assertThat(sqlError.getSQLState())
                                                        .isEqualTo("23502");

                                        // ④ 違反したカラムがuser_idであることを確認
                                        assertThat(sqlError.getServerErrorMessage())
                                                        .isNotNull();

                                        assertThat(sqlError.getServerErrorMessage().getColumn())
                                                        .isEqualTo("user_id");
                                });
        }

        private Instant databaseClock() {
                return jdbcTemplate.queryForObject(
                                "SELECT clock_timestamp()",
                                OffsetDateTime.class).toInstant();
        }

        private long countMemoRows(Long memoId) {
                return jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM memos WHERE id = ?",
                                Long.class,
                                memoId);
        }

}