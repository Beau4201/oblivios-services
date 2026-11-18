# Security policy

ObliviOS is published as source only: no build, image or update service is offered, and no device is
supported. This policy covers the source in this repository.

## Reporting a vulnerability

Open an issue, or send an email to `beau.ten.berge@gmail.com`. An issue is public: if you would rather the
problem is not public before it is fixed, use the email. Plain text is fine; you can encrypt it with the PGP
key below if you want to, but you do not have to.

- Key: user id `ObliviOS Security <beau.ten.berge@gmail.com>`, ed25519 with a cv25519 encryption subkey, valid until 2028-11-16
- Fingerprint: `7F16 E467 18B2 3814 59B8  E694 4F84 B523 8F58 7493`
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
