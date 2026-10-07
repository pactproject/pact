package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.model.Grant;

record AclGrant(
        Grant grant,
        String grantor,
        boolean grantable,
        boolean grantorIsOwner,
        boolean granteeIsOwner
) {
}
