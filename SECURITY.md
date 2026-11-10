# Security policy

ObliviOS is published as source only: no build, image or update service is offered, and no device is
supported. This policy covers the source in this repository.

## Reporting a vulnerability

Do not open a public issue for a vulnerability. Send the report by email to
`beau.ten.berge@gmail.com`, encrypted with the PGP key below if you can.

- Key: `ObliviOS Security <beau.ten.berge@gmail.com>`, ed25519 with a cv25519 encryption subkey
- Fingerprint: `8E12 CA71 F0F7 C4C4 9BF3  EE74 0FF0 E5E3 6FEE 6CA9`
- Public key: `security-contact.asc` in this repository

Compare the fingerprint above with the key before you encrypt to it.

Please include what is affected (file, patch or package), how to reproduce the problem, and the impact you
expect.

## What to expect

- A first reply within 3 days.
- The maintainer agrees a disclosure date with you. There is no fixed time to a fix.
- A reporter is credited in the fix unless they ask not to be.

There is no bug bounty.

## Scope

In scope: the patches, services and scripts in the ObliviOS repositories. A weakness that exists in
upstream GrapheneOS or AOSP is better reported to those projects; tell us as well if our patches make it
worse.
