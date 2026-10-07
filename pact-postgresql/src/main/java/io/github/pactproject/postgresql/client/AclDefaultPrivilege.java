package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;

record AclDefaultPrivilege(
        DefaultPrivilegeGrant grant,
        String grantor,
        boolean grantable,
        boolean granteeIsOwner
) {
}
