package com.github.maayyu.personaldevproject.memo.entity;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.github.maayyu.personaldevproject.organization.entity.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "memo_organizations")
public class MemoOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "memo_organizations_id_generator")
    @SequenceGenerator(name = "memo_organizations_id_generator", sequenceName = "memo_organizations_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "memo_id", nullable = false)
    private Memo memo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 20)
    private RelationType relationType;

    @Column(name = "ai_process_id")
    private Long aiProcessId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected MemoOrganization() {
    }

    public MemoOrganization(Memo memo, Organization organization, RelationType relationType, Long aiProcessId) {
        this.memo = memo;
        this.organization = organization;
        this.relationType = relationType;
        this.aiProcessId = aiProcessId;
    }

    public Long getId() {
        return id;
    }

    public Memo getMemo() {
        return memo;
    }

    public Organization getOrganization() {
        return organization;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public Long getAiProcessId() {
        return aiProcessId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
