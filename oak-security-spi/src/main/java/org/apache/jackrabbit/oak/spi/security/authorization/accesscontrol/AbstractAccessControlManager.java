/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.security.authorization.accesscontrol;

import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.authorization.PrivilegeCollection;
import org.apache.jackrabbit.api.security.authorization.PrivilegeManager;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.authorization.AuthorizationConfiguration;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.PermissionAware;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.PermissionProvider;
import org.apache.jackrabbit.oak.spi.security.authorization.permission.Permissions;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeBits;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeBitsProvider;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeConfiguration;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.Privilege;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Default implementation of the {@code JackrabbitAccessControlManager} interface.
 * This implementation covers both editing access control content by path and
 * by {@code Principal} resulting both in the same content structure.
 */
public abstract class AbstractAccessControlManager implements JackrabbitAccessControlManager, AccessControlConstants {

    private static final Logger log = LoggerFactory.getLogger(AbstractAccessControlManager.class);

    private final Root root;
    private final String workspaceName;
    private final NamePathMapper namePathMapper;
    private final AuthorizationConfiguration config;
    private final PrivilegeManager privilegeManager;

    private PermissionProvider permissionProvider;
    private PrivilegeBitsProvider privilegeBitsProvider;
    private boolean doRefresh = false;

    protected AbstractAccessControlManager(@NotNull Root root,
                                           @NotNull NamePathMapper namePathMapper,
                                           @NotNull SecurityProvider securityProvider) {
        this.root = root;
        this.workspaceName = root.getContentSession().getWorkspaceName();
        this.namePathMapper = namePathMapper;

        privilegeManager = securityProvider.getConfiguration(PrivilegeConfiguration.class).getPrivilegeManager(root, namePathMapper);
        config = securityProvider.getConfiguration(AuthorizationConfiguration.class);
    }

    //-----------------------------------------------< AccessControlManager >---
    @NotNull
    @Override
    public Privilege[] getSupportedPrivileges(@Nullable String absPath) throws RepositoryException {
        getTree(getOakPath(absPath), Permissions.NO_PERMISSION, false);
        return privilegeManager.getRegisteredPrivileges();
    }

    @NotNull
    @Override
    public Privilege privilegeFromName(@NotNull String privilegeName) throws RepositoryException {
        return privilegeManager.getPrivilege(privilegeName);
    }

    @Override
    public boolean hasPrivileges(@Nullable String absPath, @Nullable Privilege[] privileges) throws RepositoryException {
        return hasPrivileges(absPath, privileges, getPermissionProvider(), Permissions.NO_PERMISSION, false);
    }

    @NotNull
    @Override
    public Privilege[] getPrivileges(@Nullable String absPath) throws RepositoryException {
        return getPrivileges(absPath, getPermissionProvider(), Permissions.NO_PERMISSION);
    }

    //-------------------------------------< JackrabbitAccessControlManager >---
    @Override
    public boolean hasPrivileges(@Nullable String absPath, @NotNull Set<Principal> principals, @Nullable Privilege[] privileges) throws RepositoryException {
        if (getPrincipals().equals(principals)) {
            return hasPrivileges(absPath, privileges);
        } else {
            PermissionProvider provider = config.getPermissionProvider(root, workspaceName, principals);
            return hasPrivileges(absPath, privileges, provider, Permissions.READ_ACCESS_CONTROL, false);
        }
    }

    @NotNull
    @Override
    public Privilege[] getPrivileges(@Nullable String absPath, @NotNull Set<Principal> principals) throws RepositoryException {
        if (getPrincipals().equals(principals)) {
            return getPrivileges(absPath);
        } else {
            PermissionProvider provider = config.getPermissionProvider(root, workspaceName, principals);
            return getPrivileges(absPath, provider, Permissions.READ_ACCESS_CONTROL);
        }
    }

    @Override
    public @NotNull PrivilegeCollection getPrivilegeCollection(@Nullable String absPath) throws RepositoryException {
        return getPrivilegeCollection(absPath, getPermissionProvider(), Permissions.NO_PERMISSION);
    }

    @Override
    public @NotNull PrivilegeCollection getPrivilegeCollection(@Nullable String absPath, @NotNull Set<Principal> principals) throws RepositoryException {
        if (getPrincipals().equals(principals)) {
            return getPrivilegeCollection(absPath);
        } else {
            PermissionProvider provider = config.getPermissionProvider(root, workspaceName, principals);
            return getPrivilegeCollection(absPath, provider, Permissions.READ_ACCESS_CONTROL);
        }
    }

    //----------------------------------------------------------< protected >---
    @NotNull
    protected AuthorizationConfiguration getConfig() {
        return config;
    }

    @NotNull
    protected Root getRoot() {
        return root;
    }

    @NotNull
    protected Root getLatestRoot() {
        return root.getContentSession().getLatestRoot();
    }

    @NotNull
    protected NamePathMapper getNamePathMapper() {
        return namePathMapper;
    }

    @NotNull
    protected PrivilegeManager getPrivilegeManager() {
        return privilegeManager;
    }
    
    @NotNull
    protected PrivilegeBitsProvider getPrivilegeBitsProvider() {
        if (privilegeBitsProvider == null) {
            privilegeBitsProvider = new PrivilegeBitsProvider(root);
        }
        return privilegeBitsProvider;
    }

    @Nullable
    protected String getOakPath(@Nullable String jcrPath) throws RepositoryException {
        if (jcrPath == null) {
            return null;
        } else {
            String oakPath = namePathMapper.getOakPath(jcrPath);
            if (oakPath == null || !PathUtils.isAbsolute(oakPath)) {
                throw new RepositoryException("Failed to resolve JCR path " + jcrPath);
            }
            return oakPath;
        }
    }

    @NotNull
    protected Tree getTree(@Nullable String oakPath, long permissions, boolean checkAcContent) throws RepositoryException {
        Tree tree = (oakPath == null) ? root.getTree("/") : root.getTree(oakPath);
        if (!tree.exists()) {
            throw new PathNotFoundException("No tree at " + oakPath);
        }
        if (permissions != Permissions.NO_PERMISSION) {
            // check permissions
            checkPermissions((oakPath == null) ? null : tree, permissions);
        }
        // check if the tree defines access controlled content
        if (checkAcContent && config.getContext().definesTree(tree)) {
            throw new AccessControlException("Tree " + tree.getPath() + " defines access control content.");
        }
        return tree;
    }

    @NotNull
    protected PermissionProvider getPermissionProvider() {
        if (permissionProvider == null) {
            if (root instanceof PermissionAware) {
                permissionProvider = ((PermissionAware) root).getPermissionProvider();
            } else {
                permissionProvider = config.getPermissionProvider(root, workspaceName, getPrincipals());
                doRefresh = true;
            }
        } else {
            if (doRefresh) {
                permissionProvider.refresh();
            }
        }
        return permissionProvider;
    }

    //------------------------------------------------------------< private >---
    @NotNull
    private Set<Principal> getPrincipals() {
        return root.getContentSession().getAuthInfo().getPrincipals();
    }

    private void checkPermissions(@Nullable Tree tree, long permissions) throws AccessDeniedException {
        boolean isGranted;
        if (tree == null) {
            isGranted = getPermissionProvider().getRepositoryPermission().isGranted(permissions);
        } else {
            isGranted = getPermissionProvider().isGranted(tree, null, permissions);
        }
        if (!isGranted) {
            throw new AccessDeniedException("Access denied.");
        }
    }

    @NotNull
    private Set<String> getPrivilegeNames(@Nullable String absPath, @NotNull PermissionProvider provider, long permissions) throws RepositoryException {
        Tree tree;
        if (absPath == null) {
            tree = null;
            if (permissions != Permissions.NO_PERMISSION) {
                checkPermissions(null, permissions);
            }
        } else {
            tree = getTree(getOakPath(absPath), permissions, false);
        }
        return provider.getPrivileges(tree);
    }

    @NotNull
    private Privilege[] getPrivileges(@NotNull Set<String> privilegeNames) throws RepositoryException {
        if (privilegeNames.isEmpty()) {
            return new Privilege[0];
        } else {
            Set<Privilege> privileges = new HashSet<>(privilegeNames.size());
            for (String name : privilegeNames) {
                privileges.add(privilegeManager.getPrivilege(namePathMapper.getJcrName(name)));
            }
            return privileges.toArray(new Privilege[0]);
        }
    }
    
    @NotNull
    private Privilege[] getPrivileges(@Nullable String absPath,
                                      @NotNull PermissionProvider provider,
                                      long permissions) throws RepositoryException {
        return getPrivileges(getPrivilegeNames(absPath, provider, permissions));
    }

    private boolean hasPrivileges(@Nullable String absPath, @Nullable Privilege[] privileges,
                                  @NotNull PermissionProvider provider, long permissions,
                                  boolean checkAcContent) throws RepositoryException {
        Tree tree;
        if (absPath == null) {
            tree = null;
            if (permissions != Permissions.NO_PERMISSION) {
                checkPermissions(null, permissions);
            }
        } else {
            tree = getTree(getOakPath(absPath), permissions, checkAcContent);
        }
        if (privileges == null || privileges.length == 0) {
            // null or empty privilege array -> return true
            log.debug("No privileges passed -> allowed.");
            return true;
        } else {
            String[] jcrNames = Arrays.stream(privileges).filter(Objects::nonNull).map(Privilege::getName).toArray(String[]::new);
            Set<String> privilegeNames = PrivilegeUtil.getOakNames(jcrNames, namePathMapper);
            return provider.hasPrivileges(tree, privilegeNames.toArray(new String[0]));
        }
    }

    @NotNull
    private PrivilegeCollection getPrivilegeCollection(@Nullable String absPath, @NotNull PermissionProvider provider, long permissions) throws RepositoryException {
        Set<String> pNames = getPrivilegeNames(absPath, provider, permissions);
        return new PrivilegeCollection() {
            @Override
            public Privilege[] getPrivileges() throws RepositoryException {
                return AbstractAccessControlManager.this.getPrivileges(pNames);
            }

            @Override
            public boolean includes(@NotNull String... privilegeNames) throws RepositoryException {
                if (privilegeNames.length == 0) {
                    return true;
                }
                if (pNames.isEmpty()) {
                    return false;
                }
                PrivilegeBitsProvider pbp = getPrivilegeBitsProvider();
                PrivilegeBits toTest = pbp.getBits(PrivilegeUtil.getOakNames(privilegeNames, getNamePathMapper()), true);
                PrivilegeBits bits = pbp.getBits(pNames);
                return bits.includes(toTest);
            }
        };
    }
}
