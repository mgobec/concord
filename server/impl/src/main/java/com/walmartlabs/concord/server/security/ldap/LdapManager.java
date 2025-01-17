package com.walmartlabs.concord.server.security.ldap;

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

import com.walmartlabs.concord.server.cfg.LdapConfiguration;
import com.walmartlabs.concord.server.console.UserSearchResult;
import org.apache.shiro.realm.ldap.LdapContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;
import java.util.*;
import java.util.function.Function;

@Named
@Singleton
public class LdapManager {

    private static final Logger log = LoggerFactory.getLogger(LdapManager.class);

    private static final String MEMBER_OF_ATTR = "memberOf"; // TODO move to cfg
    private static final String DISPLAY_NAME_ATTR = "displayName"; // TODO move to cfg

    private final LdapConfiguration cfg;
    private final LdapContextFactory ctxFactory;

    @Inject
    public LdapManager(LdapConfiguration cfg,
                       ConcordLdapContextFactory ctxFactory) {

        this.cfg = cfg;
        this.ctxFactory = ctxFactory;
    }

    public List<UserSearchResult> search(String filter) throws NamingException {
        return search(filter, cfg.getUserSearchFilter(), new String[]{cfg.getUserPrincipalNameProperty(), DISPLAY_NAME_ATTR},
                attrs -> {
                    String upn = attrs.get(cfg.getUserPrincipalNameProperty());
                    return new UserSearchResult(getUsername(upn), getDomain(upn), attrs.get(DISPLAY_NAME_ATTR));
                });
    }

    public List<LdapGroupSearchResult> searchGroups(String filter) throws NamingException {
        return search(filter, cfg.getGroupSearchFilter(), new String[]{cfg.getGroupNameProperty(), cfg.getGroupDisplayNameProperty()},
                attrs -> LdapGroupSearchResult.builder()
                        .groupName(attrs.get(cfg.getGroupNameProperty()))
                        .displayName(attrs.getOrDefault(cfg.getGroupDisplayNameProperty(), "n/a"))
                        .build());
    }

    public Set<String> getGroups(String username, String domain) throws NamingException {
        LdapContext ctx = null;
        try {
            ctx = ctxFactory.getSystemLdapContext();
            return getGroups(ctx, username, domain);
        } catch (Exception e) {
            log.warn("getGroups ['{}'] -> error while retrieving LDAP data: {}", username, e.getMessage(), e);
            throw e;
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    public Set<String> getGroups(LdapContext ctx, String username, String domain) throws NamingException {
        SearchControls ctls = new SearchControls();
        ctls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        ctls.setReturningAttributes(new String[]{MEMBER_OF_ATTR});

        Object[] args = new Object[]{normalizeUsername(username, domain)};
        NamingEnumeration answer = ctx.search(cfg.getSearchBase(), cfg.getPrincipalSearchFilter(), args, ctls);
        if (!answer.hasMoreElements()) {
            return null;
        }

        Set<String> result = new HashSet<>();
        while (answer.hasMoreElements()) {
            SearchResult sr = (SearchResult) answer.next();

            Attributes attrs = sr.getAttributes();
            if (attrs != null) {
                NamingEnumeration ae = attrs.getAll();
                while (ae.hasMore()) {
                    Attribute attr = (Attribute) ae.next();
                    if (MEMBER_OF_ATTR.equals(attr.getID())) {
                        result.addAll(LdapUtils.getAllAttributeValues(attr));
                        break;
                    }
                }
            }
        }
        return result;
    }

    public LdapPrincipal getPrincipal(String username, String domain) throws NamingException {
        LdapContext ctx = null;
        try {
            ctx = ctxFactory.getSystemLdapContext();
            return getPrincipal(ctx, username, domain);
        } catch (Exception e) {
            log.warn("getPrincipal ['{}', '{}'] -> error while retrieving LDAP data: {}", username, domain, e.getMessage(), e);
            throw e;
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    private LdapPrincipal getPrincipal(LdapContext ctx, String username, String domain) throws NamingException {
        SearchControls ctls = new SearchControls();
        ctls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        if (cfg.getReturningAttributes() != null && !cfg.getReturningAttributes().isEmpty()) {
            ctls.setReturningAttributes(cfg.getReturningAttributes().toArray(new String[0]));
        }

        Object[] args = new Object[]{normalizeUsername(username, domain)};
        NamingEnumeration answer = ctx.search(cfg.getSearchBase(), cfg.getPrincipalSearchFilter(), args, ctls);
        if (!answer.hasMoreElements()) {
            return null;
        }

        LdapPrincipalBuilder b = new LdapPrincipalBuilder();

        SearchResult sr = (SearchResult) answer.next();
        b.nameInNamespace(sr.getNameInNamespace());

        Attributes attrs = sr.getAttributes();
        if (attrs != null) {
            NamingEnumeration ae = attrs.getAll();
            while (ae.hasMore()) {
                Attribute attr = (Attribute) ae.next();
                processAttribute(b, attr);
            }
        }

        if (answer.hasMoreElements()) {
            throw new RuntimeException("LDAP error, non unique result found for username: '" + username + "'. " +
                    "Try using a fully-qualified username.");
        }

        LdapPrincipal result = b.build();
        if (!getUsername(username).equals(result.getUsername())) {
            throw new RuntimeException("LDAP error, requested username '" + username + "' and result username mismatch '" + result.getUsername() + "' (" + result.getUserPrincipalName() + ")");
        }

        return result;
    }

    private <E> List<E> search(String filter, String searchFilter, String[] returningAttributes, Function<Map<String, String>, E> converter) throws NamingException {
        LdapContext ctx = null;
        try {
            ctx = ctxFactory.getSystemLdapContext();

            SearchControls ctls = new SearchControls();
            ctls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            ctls.setReturningAttributes(returningAttributes);
            ctls.setCountLimit(10);
            Object[] args = new Object[]{filter};

            NamingEnumeration answer = ctx.search(cfg.getSearchBase(), searchFilter, args, ctls);
            if (!answer.hasMoreElements()) {
                return Collections.emptyList();
            }

            List<E> result = new ArrayList<>();
            while (answer.hasMoreElements()) {
                SearchResult sr = (SearchResult) answer.next();
                Attributes attrs = sr.getAttributes();
                if (attrs != null) {
                    NamingEnumeration ae = attrs.getAll();
                    Map<String, String> attributes = new HashMap<>();
                    while (ae.hasMore()) {
                        Attribute attr = (Attribute) ae.next();
                        if (attr.size() == 0) {
                            continue;
                        }
                        String id = attr.getID();
                        attributes.put(id, attr.get().toString());
                    }

                    result.add(converter.apply(attributes));
                }
            }
            return result;
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    private void processAttribute(LdapPrincipalBuilder b, Attribute attr) throws NamingException {
        String id = attr.getID();
        Object v = attr.get();

        boolean matched = false;
        if (id.equals(cfg.getUserPrincipalNameProperty())) {
            String upn = v.toString();
            b.userPrincipalName(upn);
            b.domain(getDomain(upn));
            matched = true;
        }

        if (id.equals(cfg.getUsernameProperty())) {
            String username = v.toString();
            b.username(getUsername(username));
            matched = true;
        }

        if (id.equals(cfg.getMailProperty())) {
            b.email(v.toString());
            b.addAttribute(id, v.toString());
            matched = true;
        }

        if (matched) {
            return;
        }

        switch (id) {
            case MEMBER_OF_ATTR: {
                Collection<String> names = LdapUtils.getAllAttributeValues(attr);
                b.addGroups(names);
                break;
            }
            case DISPLAY_NAME_ATTR: {
                b.displayName(v.toString());
                break;
            }
            default: {
                boolean exclude = cfg.getExcludeAttributes().contains(id);
                if (exclude) {
                    return;
                }
                Set<String> exposedAttr = cfg.getExposeAttributes();
                if (exposedAttr == null || exposedAttr.isEmpty() || exposedAttr.contains(id)) {
                    Collection<String> values = LdapUtils.getAllAttributeValues(attr);
                    if (values.size() == 1) {
                        b.addAttribute(id, values.iterator().next());
                    } else {
                        b.addAttribute(id, values);
                    }
                }
            }
        }
    }

    private static String normalizeUsername(String username, String domain) {
        if (domain == null) {
            return username;
        }

        return username + "@" + domain;
    }

    private static String getDomain(String upn) {
        int pos = upn.indexOf("@");
        if (pos > 0) {
            return upn.substring(pos + 1);
        }
        return null;
    }

    private static String getUsername(String upn) {
        int pos = upn.indexOf("@");
        if (pos > 0) {
            return upn.substring(0, pos);
        }
        return upn;
    }

    private static final class LdapPrincipalBuilder {

        private String username;
        private String domain;
        private String nameInNamespace;
        private String userPrincipalName;
        private String displayName;
        private String email;
        private Set<String> groups;
        private Map<String, Object> attributes;

        public LdapPrincipalBuilder username(String username) {
            this.username = username;
            return this;
        }

        public LdapPrincipalBuilder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public LdapPrincipalBuilder nameInNamespace(String nameInNamespace) {
            this.nameInNamespace = nameInNamespace;
            return this;
        }

        public LdapPrincipalBuilder userPrincipalName(String userPrincipalName) {
            this.userPrincipalName = userPrincipalName;
            return this;
        }

        public LdapPrincipalBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public LdapPrincipalBuilder email(String email) {
            this.email = email;
            return this;
        }

        public LdapPrincipalBuilder addGroups(Collection<String> names) {
            if (groups == null) {
                groups = new HashSet<>();
            }
            groups.addAll(names);
            return this;
        }

        public LdapPrincipalBuilder addAttribute(String k, Object v) {
            if (attributes == null) {
                attributes = new HashMap<>();
            }
            attributes.put(k, v);
            return this;
        }

        public LdapPrincipal build() {
            if (groups == null) {
                groups = Collections.emptySet();
            }

            if (attributes == null) {
                attributes = Collections.emptyMap();
            }

            return new LdapPrincipal(username, domain, nameInNamespace, userPrincipalName, displayName, email, groups, attributes);
        }
    }
}
