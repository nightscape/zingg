"""Hand labels for the CVE-cluster <-> ops-ticket coverage task.

Two label axes (see README + design discussion):
  strict  = this EXISTING ops ticket genuinely remediates THIS specific vulnerability.
            The bar for claiming a cluster is "already covered". Same control class but
            a different asset/scope does NOT qualify (we don't pretend an almost-right
            ticket closes the CVE).
  related = topically close enough to BATCH into a newly-created ops ticket, or to
            SUGGEST extending this existing ticket ("you're touching X anyway, also
            handle this CVE"). Looser; same-control-class counts.

A pair absent from both sets is a non-match on both axes (strict=0, related=0).

`EXTRA` holds true remediation links the reranker did NOT surface in its top-5
(retrieved=no) -- needed to measure recall / false negatives, not just precision.

Routine OS patch/reboot tickets are deliberately strict=0 (user: "could be, but I
would not rely on it"); tagged note=routine-os so we can re-score under the lenient
assumption later.
"""

# (cluster, ops_key) -> strict match (genuine remediation of this specific vuln)
STRICT = {
    ("USER_MANAGED_SERVICE_ACCOUNT_KEY", "STXIO-7407538"),   # WIF+gcsfuse replaces SA keys
    ("USER_MANAGED_SERVICE_ACCOUNT_KEY", "STXIO-8753354"),   # WIF replaces secret-key setup
    ("CVE-2009-70199", "STXIO-5333140"),                     # remove obsolete Java
    ("CVE-2009-70199", "STXIO-8320100"),
    ("CVE-2009-70199", "STXIO-674499"),
    ("CVE-2017-21857", "STXIO-5333140"),
    ("CVE-2017-21857", "STXIO-8320100"),
    ("CVE-2017-21857", "STXIO-7926534"),
    ("CVE-2017-21857", "STXIO-674499"),                      # remove obsolete Java (DEV/TST/SIT)
    ("CVE-2017-21857", "STXIO-8393962"),                     # update Java versions on hosts
    ("CVE-2009-81041", "STXIO-5333140"),
    ("CVE-2008-64669", "STXIO-5333140"),
    ("certificate-common-name-mismatch", "STXIO-7132828"),   # fixes cert alternate names
    ("linux-rhel-obsolete-version", "STXIO-1292640"),        # RHEL7.9 -> 8.8
    ("linux-rhel-obsolete-version", "STXIO-3074931"),        # RHEL 9 upgrade
    ("linux-rhel-obsolete-version", "STXIO-2738989"),        # upgrade to RHEL 8
    ("linux-rhel-obsolete-version", "STXIO-5972718"),        # update Zeppelin to RHEL8
    ("ADMIN_SERVICE_ACCOUNT", "STXIO-554155"),               # identical SA-over-privilege finding
    ("SQL_LOG_STATEMENT", "STXIO-2429710"),                  # known backlink
    ("SQL_INSTANCE_NOT_MONITORED", "STXIO-5624497"),         # known backlink
    ("SERVICE_ACCOUNT_KEY_NOT_ROTATED", "STXIO-2545331"),    # 90-day key rotation setup
    ("SERVICE_ACCOUNT_KEY_NOT_ROTATED", "STXIO-7693175"),    # rotate expiring SA keys
    ("SERVICE_ACCOUNT_KEY_NOT_ROTATED", "STXIO-8384440"),
    ("SERVICE_ACCOUNT_KEY_NOT_ROTATED", "STXIO-8895118"),
}

# Topically related: batch / "extend this ticket" candidates (strict-0 by definition here)
RELATED = {
    ("certificate-common-name-mismatch", "STXIO-8684048"),   # enable TLS, create host cert
    ("SQL_INSTANCE_NOT_MONITORED", "STXIO-3932852"),         # generic "Monitoring solutions"
    ("SERVICE_ACCOUNT_KEY_NOT_ROTATED", "STXIO-7407538"),    # WIF eliminates keys -> no rotation
    ("1.1.2.2.2. Ensure nodev option set on /dev/shm partition", "STXIO-610936"),   # /var mount hardening
    ("1.1.2.2.2. Ensure nodev option set on /dev/shm partition", "STXIO-8896688"),
    ("1.1.2.2.3. Ensure nosuid option set on /dev/shm partition", "STXIO-610936"),
    ("1.1.2.2.3. Ensure nosuid option set on /dev/shm partition", "STXIO-8896688"),
    ("9.3.4. (L1) Ensure 'Windows Firewall: Public: Settings: Apply local firewall rules' is set to 'No'", "STXIO-1907212"),  # firewall domain
    # NB: snmp-cleartext-credential -> Informatica truststore password (STXIO-4776379/
    # 7596106) is NOT related: same control class but different systems, can't be solved
    # in one swoop. strict=0 AND related=0 (user-confirmed). It stays a model FP.
    # routine OS patching for the kernel CVE -- coarse, "extend" candidate only
    ("CVE-2010-51108", "STXIO-1615959"),
    ("CVE-2010-51108", "STXIO-6877697"),
    ("CVE-2010-51108", "STXIO-6801614"),
    ("CVE-2010-51108", "STXIO-1408908"),
    ("CVE-2010-51108", "STXIO-1239896"),
}

# note tags keyed by (cluster, ops_key); free-form, for sensitivity analysis
NOTES = {
    ("CVE-2010-51108", "STXIO-1615959"): "routine-os",
    ("CVE-2010-51108", "STXIO-6877697"): "routine-os",
    ("CVE-2010-51108", "STXIO-6801614"): "routine-os",
    ("CVE-2010-51108", "STXIO-1408908"): "routine-os",
    ("CVE-2010-51108", "STXIO-1239896"): "routine-os",
}

# True remediation links the reranker did NOT surface in top-5 (retrieved=no).
# Each: (cluster, ops_key, ops_summary). strict=1. Measures recall / false negatives.
EXTRA = [
    ("CVE-2017-68001", "STXIO-5333140", "PRD: remove obsolete Java"),
    ("CVE-2017-68001", "STXIO-8320100", "SIM/PFB: remove obsolete Java"),
    ("CVE-2017-68001", "STXIO-674499",  "DEV/TST/SIT: remove obsolete Java"),
    ("CVE-2022-59237", "STXIO-5333140", "PRD: remove obsolete Java"),
]
