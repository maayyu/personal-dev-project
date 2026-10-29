package com.github.maayyu.personaldevproject.organization.entity;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.github.maayyu.personaldevproject.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "organizations_id_generator")
    @SequenceGenerator(name = "organizations_id_generator", sequenceName = "organizations_id_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(User user, Long parentId, String name, String normalizedName) {
        this.user = user;
        this.parentId = parentId;
        this.name = name;
        this.normalizedName = normalizedName;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
