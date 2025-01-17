package com.walmartlabs.concord.server.org;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.server.audit.AuditAction;
import com.walmartlabs.concord.server.audit.AuditLog;
import com.walmartlabs.concord.server.audit.AuditObject;
import com.walmartlabs.concord.server.metrics.WithTimer;
import com.walmartlabs.concord.server.org.project.DiffUtils;
import com.walmartlabs.concord.server.org.team.TeamDao;
import com.walmartlabs.concord.server.org.team.TeamManager;
import com.walmartlabs.concord.server.org.team.TeamRole;
import com.walmartlabs.concord.server.policy.EntityAction;
import com.walmartlabs.concord.server.policy.EntityType;
import com.walmartlabs.concord.server.policy.PolicyManager;
import com.walmartlabs.concord.server.policy.PolicyUtils;
import com.walmartlabs.concord.server.security.Roles;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.user.UserEntry;
import com.walmartlabs.concord.server.user.UserManager;
import com.walmartlabs.concord.server.user.UserType;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.UnauthorizedException;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;
import java.util.UUID;

@Named
public class OrganizationManager {

    // as defined in com/walmartlabs/concord/server/db/0.48.0.xml
    public static final UUID DEFAULT_ORG_ID = UUID.fromString("0fac1b18-d179-11e7-b3e7-d7df4543ed4f");
    public static final String DEFAULT_ORG_NAME = "Default";

    private final PolicyManager policyManager;
    private final OrganizationDao orgDao;
    private final TeamDao teamDao;
    private final UserManager userManager;
    private final AuditLog auditLog;

    @Inject
    public OrganizationManager(PolicyManager policyManager,
                               OrganizationDao orgDao,
                               TeamDao teamDao,
                               UserManager userManager,
                               AuditLog auditLog) {

        this.policyManager = policyManager;
        this.orgDao = orgDao;
        this.teamDao = teamDao;
        this.userManager = userManager;
        this.auditLog = auditLog;
    }

    public UUID create(OrganizationEntry entry) {
        assertAdmin();

        UserEntry owner = getOwner(entry.getOwner(), UserPrincipal.assertCurrent().getUser());

        policyManager.checkEntity(null, null, EntityType.ORGANIZATION, EntityAction.CREATE, owner, PolicyUtils.toMap(entry));

        UUID id = orgDao.txResult(tx -> {
            UUID orgId = orgDao.insert(entry.getName(), owner.getId(), entry.getVisibility(), entry.getMeta(), entry.getCfg());

            // ...add the owner user to the default new as an OWNER
            UUID teamId = teamDao.insert(tx, orgId, TeamManager.DEFAULT_TEAM_NAME, "Default team");
            teamDao.upsertUser(tx, teamId, owner.getId(), TeamRole.OWNER);

            return orgId;
        });

        Map<String, Object> changes = DiffUtils.compare(null, entry);
        addAuditLog(AuditAction.CREATE,
                id,
                entry.getName(),
                changes);

        return id;
    }

    public void update(UUID orgId, OrganizationEntry entry) {
        OrganizationEntry prevEntry = assertUpdateAccess(orgId);

        UserEntry owner = getOwner(entry.getOwner(), null);

        policyManager.checkEntity(orgId, null, EntityType.ORGANIZATION, EntityAction.UPDATE, owner, PolicyUtils.toMap(entry));

        UUID ownerId = owner != null ? owner.getId() : null;
        orgDao.update(orgId, entry.getName(), ownerId, entry.getVisibility(), entry.getMeta(), entry.getCfg());

        OrganizationEntry newEntry = orgDao.get(orgId);

        Map<String, Object> changes = DiffUtils.compare(prevEntry, newEntry);
        addAuditLog(
                AuditAction.UPDATE,
                prevEntry.getId(),
                prevEntry.getName(),
                changes);
    }

    public void delete(String orgName) {
        assertAdmin();

        OrganizationEntry org = assertExisting(null, orgName);

        orgDao.delete(org.getId());

        addAuditLog(
                AuditAction.DELETE,
                org.getId(),
                org.getName(),
                null);
    }

    public OrganizationEntry assertExisting(UUID orgId, String orgName) {
        if (orgId != null) {
            OrganizationEntry e = orgDao.get(orgId);
            if (e == null) {
                throw new ValidationErrorsException("Organization not found: " + orgId);
            }
            return e;
        }

        if (orgName != null) {
            OrganizationEntry e = orgDao.getByName(orgName);
            if (e == null) {
                throw new ValidationErrorsException("Organization not found: " + orgName);
            }
            return e;
        }

        throw new ValidationErrorsException("Organization ID or name is required");
    }

    public OrganizationEntry assertAccess(UUID orgId, boolean orgMembersOnly) {
        return assertAccess(orgId, null, orgMembersOnly);
    }

    public OrganizationEntry assertAccess(String orgName, boolean orgMembersOnly) {
        return assertAccess(null, orgName, orgMembersOnly);
    }

    @WithTimer
    public OrganizationEntry assertAccess(UUID orgId, String name, boolean orgMembersOnly) {
        OrganizationEntry e = assertExisting(orgId, name);

        if (Roles.isAdmin()) {
            // an admin can access any organization
            return e;
        }

        if (Roles.isGlobalReader() || Roles.isGlobalWriter()) {
            return e;
        }

        UserPrincipal p = UserPrincipal.assertCurrent();

        EntityOwner owner = e.getOwner();
        if (ResourceAccessUtils.isSame(p, owner)) {
            // the owner can do anything with his organization
            return e;
        }

        if (orgMembersOnly) {
            if (!userManager.isInOrganization(e.getId())) {
                throw new UnauthorizedException("The current user (" + p.getUsername() + ") doesn't belong to the specified organization: " + e.getName());
            }
        }

        return e;
    }

    private OrganizationEntry assertUpdateAccess(UUID orgId) {
        OrganizationEntry entry = assertExisting(orgId, null);

        UserEntry owner = getOwner(entry.getOwner(), null);
        UUID ownerId = owner != null ? owner.getId() : null;
        UserPrincipal p = UserPrincipal.assertCurrent();
        if (p.getId().equals(ownerId)) {
            return entry;
        }

        if (!Roles.isAdmin()) {
            throw new AuthorizationException("Only admins or owners are allowed to update organizations");
        }
        return entry;
    }

    private static void assertAdmin() {
        if (!Roles.isAdmin()) {
            throw new AuthorizationException("Only admins are allowed to update organizations");
        }
    }

    private UserEntry getOwner(EntityOwner owner, UserEntry defaultOwner) {
        if (owner == null) {
            return defaultOwner;
        }

        if (owner.id() != null) {
            return userManager.get(owner.id())
                    .orElseThrow(() -> new ValidationErrorsException("User not found: " + owner.id()));
        }

        if (owner.username() != null) {
            UserType t = owner.userType() != null ? owner.userType() : UserPrincipal.assertCurrent().getType();
            return userManager.getOrCreate(owner.username(), owner.userDomain(), t);
        }

        return defaultOwner;
    }

    private void addAuditLog(AuditAction auditAction, UUID orgId, String orgName, Map<String, Object> changes) {
        auditLog.add(AuditObject.ORGANIZATION, auditAction)
                .field("orgId", orgId)
                .field("orgName", orgName)
                .field("changes", changes)
                .log();
    }
}
