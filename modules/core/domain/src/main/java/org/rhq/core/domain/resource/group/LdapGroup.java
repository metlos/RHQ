/*
 * RHQ Management Platform
 * Copyright (C) 2005-2009 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.rhq.core.domain.resource.group;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.rhq.core.domain.authz.Role;

/**
 * @author paji
 *
 */
@Entity
@NamedQueries( { @NamedQuery(name = LdapGroup.DELETE_BY_ID, query = "DELETE FROM LdapGroup an WHERE an.id IN ( :ids )") })
@Table(name = "RHQ_ROLE_LDAP_GROUP")
@SequenceGenerator(name = "id", sequenceName = "RHQ_ROLE_LDAP_GROUP_ID_SEQ", allocationSize = 100)
@XmlAccessorType(XmlAccessType.FIELD)
public class LdapGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String DELETE_BY_ID = "LdapGroup.deleteById";
    @Id
    @Column(name = "ID", nullable = false)
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "id")
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "ROLE_ID", referencedColumnName = "ID", nullable = false)
    private Role role;

    @Column(name = "LDAP_GROUP_NAME", nullable = false)
    private String name;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Transient
    private String description = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    @Override
    public int hashCode() {
        return getName().hashCode() + 17 * ((role != null) ? role.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof LdapGroup)) {
            return false;
        }
        LdapGroup grp = (LdapGroup) o;
        if (!getName().equals(grp.getName())) {
            return false;
        }

        if (getRole() != null) {
            return getRole().equals(grp.getRole());
        }

        return grp.getRole() == null;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }
}
