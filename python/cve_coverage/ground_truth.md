# Ground-truth labeling sheet

For each candidate write **1** (true remediation of this cluster) or **0** (not) in the `truth` box. A match = the ops ticket actually fixes THIS vulnerability (same component/software/misconfig). Generic infra work that merely shares a word = 0.

The 3 known backlinks are pre-marked `[1]`.

---

## `USER_MANAGED_SERVICE_ACCOUNT_KEY`  (225 CVE tickets)

**cluster text:** user managed service account key. Service accounts should not have user-managed keys because they can be easily leaked. Learn more at https://aff9c55d.internal/iam/docs/understanding-service-accounts#managing_service_account_keysProject Tags: saallowkeyscreate:EMP-79834,productlineid:0001996383,supportgroupid:0001997404,segregationarea:yellow,applicationid:aid019,costcenter:0001_b1-50318,environment:simulation

- `[ ]` **STXIO-7407538** (rerank 9.051, model=match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-554155** (rerank 8.869, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-8384440** (rerank 6.792, model=non-match) — SIMU: Rotate expiring service account keys for sa-data-push before 17.05.2026 to
- `[ ]` **STXIO-8753354** (rerank 6.672, model=match) — WIF Access Atlan Service Account 
    - _desc:_ To create a new service account that should be used for the WIF access, replacing the current secret key setup. below PR is required to be merged to apply on Dev environment.  [https://9be57e67.internal/datamesh/terraform/pull/216]     Dev: 28.4.2027  Acc & Simu: will be updated soon once pr
- `[ ]` **STXIO-8334562** (rerank 6.567, model=non-match) — Change CRM7 - "Support Group ID" for all Cloud Projects
    - _desc:_ Change CRM7 - "Support Group ID" for all Cloud Projects   From:  {code:java} app_id           = "aid019" application      = "statistix" costcenter       = "0001_b1-50318" owner            = "em723" product          = "clearing" product_line_id  = "0001996383" support_email    = "statistix-

## `CVE-2010-51108`  (175 CVE tickets)

**cluster text:** Red Hat: CVE-2010-82267: kernel: crypto: algif_aead - Revert to operating out-of-place (Multiple Advisories)

- `[ ]` **STXIO-1615959** (rerank 5.831, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-6877697** (rerank 5.704, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-6801614** (rerank 5.429, model=non-match) — ACC/DIT: Reboot Linux Servers after Patching - 20260118
- `[ ]` **STXIO-1408908** (rerank 5.411, model=non-match) — PROD: Quarterly OS Update dwhpx9 dwhpx9db01 and dwhpx9db02
- `[ ]` **STXIO-1239896** (rerank 5.411, model=non-match) — PROD: Quarterly OS Update dwhpx9 dwhpx9db01 and dwhpx9db02

## `CVE-2009-70199`  (141 CVE tickets)

**cluster text:** Azul Zulu: CVE-2009-59816: WebKit Active Code Execution Vulnerability

- `[ ]` **STXIO-5333140** (rerank 5.758, model=non-match) — PRD: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://5621ced4.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home
- `[ ]` **STXIO-8320100** (rerank 5.758, model=non-match) — SIM/PFB: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://d211ed98.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home
- `[ ]` **STXIO-1615959** (rerank 5.750, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-6877697** (rerank 5.612, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-674499** (rerank 5.599, model=non-match) — DEV/TST/SIT:  remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.

## `CVE-2017-68001`  (101 CVE tickets)

**cluster text:** Java CPU April 2024 Oracle Java SE, Oracle GraalVM Enterprise Edition vulnerability (CVE-2017-92784)

- `[ ]` **STXIO-2457117** (rerank 5.918, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 5.597, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 5.556, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-1456876** (rerank 5.162, model=non-match) — SIMU: Copy 90425b84.internal
- `[ ]` **STXIO-6166883** (rerank 5.115, model=unsure) — Oracle quarterly OS Update dwhpx8 dwhpx8db01 and dwhpx8db02
    - _desc:_ Oracle quarterly OS Release on infrastructure dwhpx8 and VMs dwhpx8db01 and dwhpx8db02 (all environments run on these VM, checked only PROD because this is one deployment only)  Planned dates are: 2026 Saturday, March 7, 2026 Saturday, June 6, 2026 Saturday, September 5, 2026 Saturday, Decem

## `CVE-2017-21857`  (37 CVE tickets)

**cluster text:** Azul Zulu: CVE-2017-63265: Vulnerability in the JavaFX component

- `[ ]` **STXIO-5333140** (rerank 5.529, model=match) — PRD: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://5621ced4.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home
- `[ ]` **STXIO-8320100** (rerank 5.482, model=match) — SIM/PFB: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://d211ed98.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home
- `[ ]` **STXIO-674499** (rerank 5.435, model=non-match) — DEV/TST/SIT:  remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.
- `[ ]` **STXIO-8393962** (rerank 5.381, model=non-match) — SIM/PFB: enable Java
    - _desc:_ With this ticket, we will update Java on DP and Classic Hosts.  Java Versions:       50_java_env.z8.88.0.20       50_java_env.z11.82.20       50_java_env.z17.60.18  {{Steps:}}  # Update Central Profile in /STXDATA/CFG/config/  # Move old Java Central Profile to the "/STXDATA/CFG/config/bkp_
- `[ ]` **STXIO-7926534** (rerank 5.245, model=match) — ACC/DIT: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://1f35dee1.internal:7183/cmf/hardware/hosts/config#q=java_home

## `1.6.2. Ensure system wide crypto policy disables sha1 hash and signature support`  (23 CVE tickets)

**cluster text:** 1.6.2. ensure system wide crypto policy disables sha1 hash and signature support

- `[ ]` **STXIO-8775371** (rerank 6.138, model=match) — CDP - Deploy Security to different systems
    - _desc:_ This ticket is more for tracking status of subtickets.  There are multiple systems to secure, but all have to follow the same principles: - On-prem: HDFS, HBase, Hive,  - GCP: Google Cloud storage, GKE, Cloud Run  The solution described in last comment, is about how to secure the same thing wh
- `[ ]` **STXIO-7046359** (rerank 5.793, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-4924535** (rerank 5.628, model=non-match) — System Policy Verification
    - _desc:_ |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total| |TEST|System Policy Verification|Must be done 2025|DP|Development|Björn Boyens|Accenture Test| |1|297|33|
- `[ ]` **STXIO-554155** (rerank 5.627, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-4374135** (rerank 5.459, model=unsure) — Cloudera OTC
    - _desc:_ Dear colleagues,  please crosscheck Excel spreadsheet (HDFS_Policies_v3.xlsx) on the sheet with the name tech_user_business_role and HDFS_PRD for your users/folders, which are marked with your Name in sheet tech_user_business_role.  We've so far identified the following users from Excel spreadsh

## `CVE-2009-81041`  (17 CVE tickets)

**cluster text:** Java CPU January 2017 Java SE, Java SE Embedded, JRockit RMI vulnerability (CVE-2009-90190)

- `[ ]` **STXIO-2457117** (rerank 5.051, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-1615959** (rerank 4.849, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-5333140** (rerank 4.733, model=match) — PRD: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://5621ced4.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home
- `[ ]` **STXIO-6603495** (rerank 4.715, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-6877697** (rerank 4.653, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat

## `snmp-generic-0001`  (16 CVE tickets)

**cluster text:** snmp generic 0001. Multiple SNMP v1 Request Handling Vulnerabilities

- `[ ]` **STXIO-935741** (rerank 5.948, model=match) — OFI throttles some of our requests
    - _desc:_ Since OFI introduced throttling, some of our requests are regularly rejcted by OFI even the DP OFI Client meets the throttling requirements.  The cause could be that transport time is not constant. The original delays between messages could be distorted. The result would be that more messages than
- `[ ]` **STXIO-7046359** (rerank 5.815, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-7407538** (rerank 5.684, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-554155** (rerank 5.654, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-7166214** (rerank 5.249, model=non-match) — Closure of minor VMT Tickets
    - _desc:_ |+[R7C-193706|https://81cdb72c.internal/browse/R7C-193706]+|Critical|In Progress|18.07.2025|02.05.2026|Bjoern Boyens|Production|dwhphad2|6.2.3. Ensure all groups in /etc/passwd exist in /etc/group| |+[R7C-193952|https://81cdb72c.internal/browse/R7C-193952]+|Critical|In Progress|18.07.2025|02.05.202

## `snmp-cleartext-credential`  (10 CVE tickets)

**cluster text:** snmp cleartext credential. SNMP credentials transmitted in cleartext

- `[ ]` **STXIO-4776379** (rerank 6.571, model=non-match) — PROD:  Adjustment "30_infa_env.10.5.5"
    - _desc:_ infacmd commands fail because the password "INFA_TRUSTSTORE_PASSWORD" is stored unencrypted in "/STXDATA/CFG/config/30_infa_env.10.5.5".  Solution steps: - Encrypt the password using pmpasswd *- done* - Add the password to secure_vautl *-done* - Deploy the Ansible role stx/create_profile
- `[ ]` **STXIO-7596106** (rerank 6.451, model=non-match) — SIM/PFB: Adjustment "30_infa_env.10.5.5"
    - _desc:_ infacmd commands fail because the password "INFA_TRUSTSTORE_PASSWORD" is stored unencrypted in "/STXDATA/CFG/config/30_infa_env.10.5.5".  Solution steps: - Encrypt the password using pmpasswd *- done* - Add the password to secure_vautl *-done* - Deploy the Ansible role stx/create_profile
- `[ ]` **STXIO-1283190** (rerank 5.849, model=non-match) — PROD: Code change in gen_credentials_ad.sh to provide AID in Cloudera created te
- `[ ]` **STXIO-7407538** (rerank 5.780, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-5610292** (rerank 5.520, model=non-match) — Create possibility to store secret files for Script-fw
    - _desc:_ We need for script-fw the possibility to store secrets.     Option 1 use netrc  Option 2 use file with passwords stored in plaintext  Option 3 use file with passwords stored encrypted (+[https://27af49c8.internal/plyint/311f08e9.internal] or something similar)+  Option 4 use big data encry

## `snmp-read-0001`  (10 CVE tickets)

**cluster text:** snmp read 0001. Default or Guessable SNMP community names: public

- `[ ]` **STXIO-2457117** (rerank 4.280, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-8118569** (rerank 4.042, model=non-match) — DEV: Secret Key Rotation setup
- `[ ]` **STXIO-554155** (rerank 3.998, model=unsure) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-8334562** (rerank 3.928, model=non-match) — Change CRM7 - "Support Group ID" for all Cloud Projects
    - _desc:_ Change CRM7 - "Support Group ID" for all Cloud Projects   From:  {code:java} app_id           = "aid019" application      = "statistix" costcenter       = "0001_b1-50318" owner            = "em723" product          = "clearing" product_line_id  = "0001996383" support_email    = "statistix-
- `[ ]` **STXIO-6603495** (rerank 3.925, model=non-match) — ACC: Copy 90425b84.internal

## `CVE-2010-71656`  (10 CVE tickets)

**cluster text:** Microsoft Edge (Chromium-based): CVE-2010-86189: Use after free in Dawn

- `[ ]` **STXIO-7046359** (rerank 4.989, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-554155** (rerank 4.871, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-693449** (rerank 4.527, model=non-match) — Crosscheck
    - _desc:_ We need to verify that all risks are closed by new solutions.
- `[ ]` **STXIO-2457117** (rerank 4.380, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-9054677** (rerank 4.210, model=non-match) — PROD: Closure of minor VMT Tickets
    - _desc:_ Fixing of VMT Tickets is totally transparent for Ops, see Dev Ticket STXIO-1709 DEV: Closure of minor VMT Tickets - DBG DevOps Jira     No impact expected

## `apache-log4j-obsolete-version`  (7 CVE tickets)

**cluster text:** apache log4j obsolete version. Apache Log4j Obsolete Version

- `[ ]` **STXIO-2457117** (rerank 7.979, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 7.018, model=match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 6.726, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-674499** (rerank 6.539, model=match) — DEV/TST/SIT:  remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.
- `[ ]` **STXIO-1456876** (rerank 6.289, model=non-match) — SIMU: Copy 90425b84.internal

## `9.3.4. (L1) Ensure 'Windows Firewall: Public: Settings: Apply local firewall rules' is set to 'No'`  (6 CVE tickets)

**cluster text:** 9.3.4. (l1) ensure 'windows firewall: public: settings: apply local firewall rules' is set to 'no'

- `[ ]` **STXIO-7046359** (rerank 5.309, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-521546** (rerank 4.234, model=unsure) — SAFE - F5 redesign based on FW-Adequacy-Matrix-Change 
    - _desc:_ On 20.8.2023 [Criteria for Adequate / Inadequate Network Connectivityv2.17|https://0e171f54.internal/:b:/r/sites/DCM/CCI/public/Shared%20Documents/Firewall%20Process/Criteria%20for%20Adequate-Inadequate%20Network%20Connectivity%202.17.pdf]  becomes active  This requires a redesgin, because severla
- `[ ]` **STXIO-4924535** (rerank 3.627, model=non-match) — System Policy Verification
    - _desc:_ |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total| |TEST|System Policy Verification|Must be done 2025|DP|Development|Björn Boyens|Accenture Test| |1|297|33|
- `[ ]` **STXIO-554155** (rerank 3.538, model=unsure) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-1907212** (rerank 3.436, model=match) — NSS (Firewall Requests and Revalidation)
    - _desc:_ This is created based on "Tasks 2025.xlsx". @bjoern please edit and assign accordingly.       |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total|Comment| | | | | |BAU|NSS (Firewall Requests and Revalidation)|ongoing|Ove

## `certificate-common-name-mismatch`  (4 CVE tickets)

**cluster text:** certificate common name mismatch. X.509 Certificate Subject CN Does Not Match the Entity Name

- `[ ]` **STXIO-7132828** (rerank 6.108, model=match) — Fix Certificate Issue WebMethods when F5 URL is used
    - _desc:_ Enduser create UHD tickets because they see a warning when they connect via F5 URL to WebMethods. Root cause is that not all alternate names are considered in the certificate.     Please note: SIMU and PROD runing always on same server, so the certificate needs to cover the name for both alterna
- `[ ]` **STXIO-1774786** (rerank 5.479, model=non-match) — Recertification
    - _desc:_ We need to do recertification, this we can potentially script away and close recertification requests nearly automatically.
- `[ ]` **STXIO-8684048** (rerank 4.941, model=match) — Enable TSL - Informatica & Control-M Agent
    - _desc:_ Configure TLS in Informatica PowerCenter is a prerequisite for Informatica Cloudification and is also a requirment from security point of view. Since Informatica several dependencies this Service request requires several Steps per environment.     *Step 1: Create new Host certificate*  As prep
- `[ ]` **STXIO-2305620** (rerank 4.877, model=unsure) — SIMU: Google Certificate
- `[ ]` **STXIO-1836259** (rerank 4.751, model=non-match) — PROD: Google Certificate

## `linux-rhel-obsolete-version`  (4 CVE tickets)

**cluster text:** linux rhel obsolete version. Red Hat Enterprise Linux Obsolete Version

- `[1]` **STXIO-1292640** (rerank 7.993, model=match) — Switch Workload
    - _desc:_ Please switch workload for servers from RHEL 7.9 to RHEL 8.8.  Replacement: Lower: *dwhdazdpcl1 -> dwhdazdpcl5 (DIT) {color:#00875a}done{color}* *dwhdazdpcl2 -> dwhdazdpcl6 (DIT) {color:#00875a}done{color}*  *dwhdazdpcl3 -> dwhdazdpcl7 (ACC) planned for Friday, June 14 {color:#00875a}done{col
- `[ ]` **STXIO-3074931** (rerank 6.867, model=match) — OS Upgrade - RHEL 9 Upgrade
    - _desc:_ This is created based on "Tasks 2025.xlsx". @bjoern please edit and assign accordingly. |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total|Comment| | | | | |BAU|RHEL 9 Upgrade|Will come 2026 or later|Overall|Development| | 
- `[ ]` **STXIO-5972718** (rerank 6.781, model=non-match) — Update Zeppelin Server to RHEL8
    - _desc:_ Description:
- `[ ]` **STXIO-2738989** (rerank 6.687, model=unsure) — Upgrade to RHEL 8
    - _desc:_ We need to upgrade to RHEL 8, where we started with CDP, we can't use RHEL 8, now we have to plan upgrade to RHEL 8. Because this need multiple steps, we have to plan this.  Agreed with Denis to upgrade to RHEL 8 until Q4/2022  Best regards Björn
- `[ ]` **STXIO-2457117** (rerank 6.552, model=non-match) — PROD: Copy 90425b84.internal

## `CVE-2010-80586`  (4 CVE tickets)

**cluster text:** Adobe Acrobat: CVE-2010-10177: Security updates available for Adobe Acrobat and Reader (APSB26-43)

- `[ ]` **STXIO-2836897** (rerank 4.991, model=non-match) — SIMU/PFB/PPR: Please install: xhtml2pdf library
- `[ ]` **STXIO-2457117** (rerank 4.628, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.344, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-693449** (rerank 4.278, model=non-match) — Crosscheck
    - _desc:_ We need to verify that all risks are closed by new solutions.
- `[ ]` **STXIO-554155** (rerank 4.266, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce

## `CVE-2008-64669`  (3 CVE tickets)

**cluster text:** Java CPU July 2015 Java SE, Java SE Embedded Libraries vulnerability (CVE-2008-74359)

- `[ ]` **STXIO-2457117** (rerank 5.210, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 5.067, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-1615959** (rerank 5.051, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-4227838** (rerank 4.906, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-5333140** (rerank 4.827, model=unsure) — PRD: remove obsolete Java
    - _desc:_ With this ticket, we will update the symbolic links zulu-8/11/17 and update the .profile files for all os users.  DP: https://5621ced4.internal:7183/cmf/login?returnUrl=%2Fcmf%2Fhardware%2Fhosts%2Fconfig%23q%3Djava_home

## `CVE-2022-59237`  (3 CVE tickets)

**cluster text:** Java CPU April 2016 Java SE, Java SE Embedded, JRockit JMX vulnerability (CVE-2022-39505)

- `[ ]` **STXIO-2457117** (rerank 5.177, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.899, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-1615959** (rerank 4.758, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-4227838** (rerank 4.691, model=unsure) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-6877697** (rerank 4.672, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat

## `1.6.3. Ensure system wide crypto policy disables sha1 hash and signature support`  (3 CVE tickets)

**cluster text:** 1.6.3. ensure system wide crypto policy disables sha1 hash and signature support

- `[ ]` **STXIO-8775371** (rerank 6.136, model=unsure) — CDP - Deploy Security to different systems
    - _desc:_ This ticket is more for tracking status of subtickets.  There are multiple systems to secure, but all have to follow the same principles: - On-prem: HDFS, HBase, Hive,  - GCP: Google Cloud storage, GKE, Cloud Run  The solution described in last comment, is about how to secure the same thing wh
- `[ ]` **STXIO-7046359** (rerank 5.759, model=unsure) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-4924535** (rerank 5.622, model=non-match) — System Policy Verification
    - _desc:_ |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total| |TEST|System Policy Verification|Must be done 2025|DP|Development|Björn Boyens|Accenture Test| |1|297|33|
- `[ ]` **STXIO-554155** (rerank 5.603, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-4374135** (rerank 5.456, model=unsure) — Cloudera OTC
    - _desc:_ Dear colleagues,  please crosscheck Excel spreadsheet (HDFS_Policies_v3.xlsx) on the sheet with the name tech_user_business_role and HDFS_PRD for your users/folders, which are marked with your Name in sheet tech_user_business_role.  We've so far identified the following users from Excel spreadsh

## `ADMIN_SERVICE_ACCOUNT`  (2 CVE tickets)

**cluster text:** admin service account. A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: productlineid:0001996383,supportgroupid:0001997404,segregationarea:yellow,applicationid:aid019,costcenter:0001_b1-50318,environment:simulation

- `[ ]` **STXIO-554155** (rerank 11.211, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-8334562** (rerank 7.555, model=non-match) — Change CRM7 - "Support Group ID" for all Cloud Projects
    - _desc:_ Change CRM7 - "Support Group ID" for all Cloud Projects   From:  {code:java} app_id           = "aid019" application      = "statistix" costcenter       = "0001_b1-50318" owner            = "em723" product          = "clearing" product_line_id  = "0001996383" support_email    = "statistix-
- `[ ]` **STXIO-7407538** (rerank 6.850, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-8684374** (rerank 6.808, model=non-match) — LUNA
    - _desc:_ We need to completely reimplement the AD structure, following the design principles outlined in the attached document from the Luna Project. Our current plan involves consolidating all accounts from our OUs under a single AID019 in the TPA/NPA account tree, which will be divided by prefix. Sub-OUs 
- `[ ]` **STXIO-4488171** (rerank 6.654, model=non-match) — Data product service account - role update
    - _desc:_ *Description:*  As highlighted by the Risk team during the implementation of CDAP (dpr004), the service account used for data product infrastructure requires additional IAM roles to enable access to data stored in linked datasets within the CDAP GCP project. These roles should be assigned by defau

## `SQL_LOG_STATEMENT`  (2 CVE tickets)

**cluster text:** sql log statement. The "log_statement" flag in this Cloud SQL instance is not set to "ddl". The value of this flag controls which SQL statements are logged. Logging helps troubleshoot operational problems and permits forensic analysis. If this flag isn't set to the correct value, relevant information might be skipped or might be hidden in too many messages. A value of 'ddl' (all data definition statements) is recommended unless otherwise directed by your organization's logging 83895b5a.internal Tags: saallowkeyscreate:EMP-79834,environment:production,productlineid:0001996383,supportgroupid:000

- `[1]` **STXIO-2429710** (rerank 12.098, model=match) — Cloud SQL Logging Misconfiguration – log_statement not set to ddl
    - _desc:_ *Description:* Correct the Cloud SQL database flag configuration for instance *db-postgres-airflow* to ensure proper SQL statement logging for operational troubleshooting and forensic analysis.   *Problem Description* The log_statement flag is not set to the recommended value ddl. This flag con
- `[ ]` **STXIO-2457117** (rerank 6.852, model=match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-554155** (rerank 6.769, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-7407538** (rerank 6.600, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-5624497** (rerank 6.372, model=non-match) — GCP Cloud SQL Misconfiguration Monitoring – Metric & Alert Setup
    - _desc:_ *Description:* Implement monitoring to detect Cloud SQL configuration changes in project *dbg-stx-infra-simu-cf* in order to reduce risk from misconfigurations impacting security, availability, and network exposure. Establish *log-based monitoring and alerting* for Cloud SQL instance configuration

## `SQL_INSTANCE_NOT_MONITORED`  (2 CVE tickets)

**cluster text:** sql instance not monitored. Misconfiguration of SQL instance options can cause security risks, like adverse impact on business continuity with the "Enable auto backups and high availability" options, or increased exposure to untrusted networks with the "Authorized networks" options. By monitoring changes to SQL instance configuration, you can help reduce the time to detect and correct c69088cc.internal Tags: saallowkeyscreate:EMP-79834,environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costcenter:0001_b1-50318,segregationarea:red

- `[1]` **STXIO-5624497** (rerank 9.981, model=match) — GCP Cloud SQL Misconfiguration Monitoring – Metric & Alert Setup
    - _desc:_ *Description:* Implement monitoring to detect Cloud SQL configuration changes in project *dbg-stx-infra-simu-cf* in order to reduce risk from misconfigurations impacting security, availability, and network exposure. Establish *log-based monitoring and alerting* for Cloud SQL instance configuration
- `[ ]` **STXIO-554155** (rerank 8.755, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-7407538** (rerank 7.575, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-3932852** (rerank 7.533, model=match) — Monitoring solutions
- `[ ]` **STXIO-2429710** (rerank 7.403, model=match) — Cloud SQL Logging Misconfiguration – log_statement not set to ddl
    - _desc:_ *Description:* Correct the Cloud SQL database flag configuration for instance *db-postgres-airflow* to ensure proper SQL statement logging for operational troubleshooting and forensic analysis.   *Problem Description* The log_statement flag is not set to the recommended value ddl. This flag con

## `SERVICE_ACCOUNT_KEY_NOT_ROTATED`  (1 CVE tickets)

**cluster text:** service account key not rotated. User-managed service account keys should be rotated every 90 days to ensure that data cannot be accessed with an old key which might have been lost, cracked, or 2b2ec065.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costcenter:0001_b1-50318,segregationarea:red

- `[ ]` **STXIO-2545331** (rerank 9.757, model=match) — Secret Key Rotation setup
    - _desc:_ Setup of Secret Key rotation on CyberArch for Transfer & Monitor projects.  these keys are used for the data transfer from On-Prem, and should be rotated each 90 days as per Governance requirements.
- `[ ]` **STXIO-7693175** (rerank 9.428, model=non-match) — PROD: Rotate expiring service account keys for sa-data-push before 17.05.2026 to
- `[ ]` **STXIO-7407538** (rerank 9.326, model=non-match) — EMP-79834 (use of Service Account Keys)
    - _desc:_ Technology change by using:   *WIF + gcsfuse* instead of *DistCP and Service Account Keys*      DistCP and Service Account Keys requires in addition  additional Service Account to rotate the keys  This Service Account required additional acceptation, due to high privileged access  for key ro
- `[ ]` **STXIO-8384440** (rerank 9.321, model=match) — SIMU: Rotate expiring service account keys for sa-data-push before 17.05.2026 to
- `[ ]` **STXIO-8895118** (rerank 9.317, model=non-match) — DEV: Rotate expiring service account keys for sa-data-push before 17.05.2026 to 

## `192519n59-1-03`  (1 CVE tickets)

**cluster text:** 192519n59 1 03

- `[ ]` **STXIO-2457117** (rerank 4.640, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.378, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-1456876** (rerank 4.269, model=non-match) — SIMU: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 4.256, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-7937647** (rerank 3.771, model=unsure) — Control-M patching
    - _desc:_ This is created based on "Tasks 2025.xlsx". @bjoern please edit and assign accordingly.       |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total|Comment| | | | | |BAU|Control-M patching|Must be done 2025|Classic|Develop

## `192519n59-1-01`  (1 CVE tickets)

**cluster text:** 192519n59 1 01

- `[ ]` **STXIO-2457117** (rerank 4.433, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.103, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 3.957, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-1456876** (rerank 3.937, model=non-match) — SIMU: Copy 90425b84.internal
- `[ ]` **STXIO-5614601** (rerank 3.813, model=non-match) — Quarterly OS Update dwhpx7 dwhpx7db01 and dwhpx7db02
    - _desc:_ Oracle quarterly OS Release on infrastructure dwhpx7 and VMs dwhpx7db01 and dwhpx7db02 (all environments run on these VM, checked only PROD because this is one deployment only)  Planned dates are: 2026 |Saturday, February 21, 2026| |Saturday, May 23, 2026| |Saturday, August 22, 2026| |Saturd

## `192519n59-1-02`  (1 CVE tickets)

**cluster text:** 192519n59 1 02

- `[ ]` **STXIO-2457117** (rerank 4.536, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.166, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 4.050, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-1456876** (rerank 4.030, model=non-match) — SIMU: Copy 90425b84.internal
- `[ ]` **STXIO-7166214** (rerank 3.959, model=non-match) — Closure of minor VMT Tickets
    - _desc:_ |+[R7C-193706|https://81cdb72c.internal/browse/R7C-193706]+|Critical|In Progress|18.07.2025|02.05.2026|Bjoern Boyens|Production|dwhphad2|6.2.3. Ensure all groups in /etc/passwd exist in /etc/group| |+[R7C-193952|https://81cdb72c.internal/browse/R7C-193952]+|Critical|In Progress|18.07.2025|02.05.202

## `1.1.2.2.2. Ensure nodev option set on /dev/shm partition`  (1 CVE tickets)

**cluster text:** 1.1.2.2.2. ensure nodev option set on /dev/shm partition

- `[ ]` **STXIO-610936** (rerank 6.829, model=non-match) — /var file system nosuid,nodev remaining PRD servers
    - _desc:_ remaining PROD servers where /var file system needs to be given nosuid,nodev flags  has been done for all prod already but some servers where down by the time when the ticket was deployed:  dwhpdpkaf13  dwhpdpkaf15  dwhpdpdevgw1  dwhpdpdevgw2     done for other PROD servers in STXIO-95
- `[ ]` **STXIO-8896688** (rerank 6.783, model=non-match) — New Role for yarn
    - _desc:_ The new Linux Security Guideline forces us to fix Cloudera Stack and add the nosuid and nodev mount options to the /var file system.  The Cloudera workers (running yarn) have a binary in /var/lib/yarn-ce which has the sticky bits set (and they need to remain effective). Example: {quote}[gy742_t1@
- `[ ]` **STXIO-1474489** (rerank 4.452, model=non-match) — HDFS Security
    - _desc:_ We need to recude permission from that all can read all data, that only technical users can read data.
- `[ ]` **STXIO-7864362** (rerank 4.285, model=non-match) — PROD: Review Master Memory size
- `[ ]` **STXIO-8265047** (rerank 4.208, model=non-match) — Restrict Permissions for M2M
    - _desc:_ We have to restrict for the users: stx_prd_f7_m2m stx_prd_t7_eex_m2m stx_prd_t7_ex_m2m stx_prd_t7_xdus_m2m stx_prd_t7_xe_m2m stx_prd_t7_xf_m2m stx_prd_t7_xham_m2m stx_prd_t7_xhan_m2m  The access totally for HDFS.

## `1.1.2.2.3. Ensure nosuid option set on /dev/shm partition`  (1 CVE tickets)

**cluster text:** 1.1.2.2.3. ensure nosuid option set on /dev/shm partition

- `[ ]` **STXIO-610936** (rerank 7.874, model=match) — /var file system nosuid,nodev remaining PRD servers
    - _desc:_ remaining PROD servers where /var file system needs to be given nosuid,nodev flags  has been done for all prod already but some servers where down by the time when the ticket was deployed:  dwhpdpkaf13  dwhpdpkaf15  dwhpdpdevgw1  dwhpdpdevgw2     done for other PROD servers in STXIO-95
- `[ ]` **STXIO-8896688** (rerank 6.642, model=non-match) — New Role for yarn
    - _desc:_ The new Linux Security Guideline forces us to fix Cloudera Stack and add the nosuid and nodev mount options to the /var file system.  The Cloudera workers (running yarn) have a binary in /var/lib/yarn-ce which has the sticky bits set (and they need to remain effective). Example: {quote}[gy742_t1@
- `[ ]` **STXIO-1474489** (rerank 5.547, model=non-match) — HDFS Security
    - _desc:_ We need to recude permission from that all can read all data, that only technical users can read data.
- `[ ]` **STXIO-7638376** (rerank 4.999, model=non-match) — tramper creation
    - _desc:_ Please develop a single automation role for creating the {*}tramper{*}, named {{{}dwh_informatica.yml{}}}. Ensure that the role is thoroughly tested on the {{dwhsandbobx1}} environment.  mkdir /tramper chmod 1777 /tramper/     Add in file: /etc/fstab this line   tmpfs                      
- `[ ]` **STXIO-8265047** (rerank 4.982, model=non-match) — Restrict Permissions for M2M
    - _desc:_ We have to restrict for the users: stx_prd_f7_m2m stx_prd_t7_eex_m2m stx_prd_t7_ex_m2m stx_prd_t7_xdus_m2m stx_prd_t7_xe_m2m stx_prd_t7_xf_m2m stx_prd_t7_xham_m2m stx_prd_t7_xhan_m2m  The access totally for HDFS.

## `3.3.7. Ensure reverse path filtering is enabled`  (1 CVE tickets)

**cluster text:** 3.3.7. ensure reverse path filtering is enabled

- `[ ]` **STXIO-8934062** (rerank 6.162, model=non-match) — SIMU/PFB/PPR: Enable TSL - Informatica & Control-M Agent
- `[ ]` **STXIO-3233071** (rerank 6.027, model=non-match) — HDFS Ranger Policies for STX_ORACLE
    - _desc:_ Create HDFS Ranger Policies for Path: <Env>/RAW/STX_ORACLE...    Level 5 must also be considered for this.  please crosscheck Excel spreadsheet ([https://0e171f54.internal/:x:/r/teams/GO365_StatistiX_IT-InfrastructureandSecurity/_layouts/15/21d01dd1.internal?sourcedoc=%7B6C95E369-5A68-4CB4-B3C
- `[ ]` **STXIO-7365902** (rerank 5.942, model=unsure) — Extend data product module and implement Inbnd/Outbnd 
    - _desc:_ We will need to extend data product module and add: inbnd and outbnd buckets. These buckets are optional,   1. Introduce 2 additional attrinutes with default value false for data products:   enable_inbnd_bucket  enable_outbnd_bucket  2. If attribute set as true create buckets (same name like
- `[ ]` **STXIO-374447** (rerank 5.829, model=non-match) — PROD: Enable TSL - Informatica & Control-M Agent
- `[ ]` **STXIO-1469225** (rerank 5.636, model=non-match) — New Oracle Schema
    - _desc:_ New Oracle database schema on repository servers for Informatica  It should be available for all environments where Informatica is running.  There should be grants for accessing  * read only schemas containing views in REPO_<env>_RO  * user actually underlzing Informatica   Name DATA_LINEAGE

## `CVE-2017-37456`  (1 CVE tickets)

**cluster text:** Vmware Spring: CVE-2017-61758: Spring Framework RCE via Data Binding

- `[ ]` **STXIO-554155** (rerank 5.188, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-7365902** (rerank 5.060, model=non-match) — Extend data product module and implement Inbnd/Outbnd 
    - _desc:_ We will need to extend data product module and add: inbnd and outbnd buckets. These buckets are optional,   1. Introduce 2 additional attrinutes with default value false for data products:   enable_inbnd_bucket  enable_outbnd_bucket  2. If attribute set as true create buckets (same name like
- `[ ]` **STXIO-1615959** (rerank 5.052, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-6877697** (rerank 4.947, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-2457117** (rerank 4.899, model=non-match) — PROD: Copy 90425b84.internal

## `CVE-2017-15502`  (1 CVE tickets)

**cluster text:** Apache Commons Text: CVE-2017-27914: Arbitrary Code Execution from Variable Interpolation

- `[ ]` **STXIO-8981940** (rerank 5.817, model=non-match) — PROD:  Execution of historical sync for cue_ex and mar_elist
- `[ ]` **STXIO-7046359** (rerank 5.512, model=unsure) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-2457117** (rerank 5.165, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-554155** (rerank 5.051, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-6603495** (rerank 5.008, model=non-match) — ACC: Copy 90425b84.internal

## `2023 REPORT PETER-7075 StatistiX AID019 20240102 v1.0`  (1 CVE tickets)

**cluster text:** 2023 report peter 7075 statistix aid019 20240102 v1.0

- `[ ]` **STXIO-8334562** (rerank 8.159, model=non-match) — Change CRM7 - "Support Group ID" for all Cloud Projects
    - _desc:_ Change CRM7 - "Support Group ID" for all Cloud Projects   From:  {code:java} app_id           = "aid019" application      = "statistix" costcenter       = "0001_b1-50318" owner            = "em723" product          = "clearing" product_line_id  = "0001996383" support_email    = "statistix-
- `[ ]` **STXIO-3957968** (rerank 7.998, model=non-match) — DP: Cleanup IIQ Entitlements Before Importing To NEXIS
    - _desc:_ Cleaning up DP IIQ entitlements before importing them to NEXIS.  Link to Excel: https://0e171f54.internal/sites/ps0392/StatistiX_Infrastructure%26Security/Shared%20Documents/Security/IAM/AID019_20250409.xlsx  Please set the due date based on your prios but keep in mind that critical applications
- `[ ]` **STXIO-5150688** (rerank 7.932, model=unsure) — DWH: Cleanup IIQ Entitlements Before Importing To NEXIS
    - _desc:_ Cleaning up DWH IIQ entitlements before importing them to NEXIS.  Link to Excel: https://0e171f54.internal/sites/ps0392/StatistiX_Infrastructure%26Security/Shared%20Documents/Security/IAM/AID019_20250409.xlsx  Please set the due date based on your prios but keep in mind that critical application
- `[ ]` **STXIO-8684374** (rerank 6.990, model=unsure) — LUNA
    - _desc:_ We need to completely reimplement the AD structure, following the design principles outlined in the attached document from the Luna Project. Our current plan involves consolidating all accounts from our OUs under a single AID019 in the TPA/NPA account tree, which will be divided by prefix. Sub-OUs 
- `[ ]` **STXIO-1800926** (rerank 6.920, model=unsure) — Implementation of Securitytool PoC
    - _desc:_ We have to write a security framework, which will answer central questions for our department. For this we have to work close together with colleagues from IT Security, Security and Cloud Security Teams.     To establish a robust solution for managing Google Cloud environments using Conjur / Cyb

## `CVE-2017-54606`  (1 CVE tickets)

**cluster text:** Cisco NX-OS: CVE-2017-86282: HTTP/2 Rapid Reset Attack Affecting Cisco Products: October 2023

- `[ ]` **STXIO-554155** (rerank 5.297, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-1406767** (rerank 4.750, model=non-match) — Cloud Workstations
    - _desc:_ |Initiative|Task|Priority|System|Activity type|Internal Responsible|Company / Developer|Jira|Count|Effort Single|Effort total| |SAFE|Cloud Workstations|Must be done Q1/2025|Cloud|Development|Andreas Sedler|Accenture Test| |1|594|66|
- `[ ]` **STXIO-7046359** (rerank 4.716, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-1615959** (rerank 4.475, model=non-match) — PROD: Reboot Linux Application Part B-Production DWH Servers (Wave 4)  after Pat
- `[ ]` **STXIO-2457117** (rerank 4.410, model=non-match) — PROD: Copy 90425b84.internal

## `CVE-2010-50083`  (1 CVE tickets)

**cluster text:** Google Chrome Vulnerability: CVE-2010-80535

- `[ ]` **STXIO-2457117** (rerank 5.075, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-2867084** (rerank 5.039, model=unsure) — F5 enable TLS1.3
    - _desc:_ making all the *.dfb11fc3.internal services TLS1.3 capable. Currently, the max. TLS version is 1.2. This was due to the OS version of the cslfxx51 F5 having a flaw when handling client cert authentication with TLS1.3 (CAs were not advertised properly).  Now since the F5 is OS-upgraded, TLS1.3 can 
- `[ ]` **STXIO-554155** (rerank 4.989, model=non-match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-6603495** (rerank 4.979, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-7132828** (rerank 4.975, model=non-match) — Fix Certificate Issue WebMethods when F5 URL is used
    - _desc:_ Enduser create UHD tickets because they see a warning when they connect via F5 URL to WebMethods. Root cause is that not all alternate names are considered in the certificate.     Please note: SIMU and PROD runing always on same server, so the certificate needs to cover the name for both alterna

## `CVE-2010-94401`  (1 CVE tickets)

**cluster text:** Google Chrome Vulnerability: CVE-2010-50266 Inappropriate implementation in V8

- `[ ]` **STXIO-2457117** (rerank 5.211, model=non-match) — PROD: Copy 90425b84.internal
- `[ ]` **STXIO-6603495** (rerank 4.890, model=non-match) — ACC: Copy 90425b84.internal
- `[ ]` **STXIO-4227838** (rerank 4.686, model=non-match) — SIT: Copy 90425b84.internal
- `[ ]` **STXIO-5088976** (rerank 4.616, model=unsure) — Analysis - Introduce PR based unit tests + SDLC improvements 
- `[ ]` **STXIO-8981940** (rerank 4.589, model=non-match) — PROD:  Execution of historical sync for cue_ex and mar_elist

## `4.2.22. Ensure sshd crypto_policy is not set`  (1 CVE tickets)

**cluster text:** 4.2.22. ensure sshd crypto policy is not set

- `[ ]` **STXIO-7046359** (rerank 5.472, model=non-match) — Risks to be covered
    - _desc:_ We need to create as long as SAFE is not finished, Risks for Firewall rules. This ticket is to trace the requirments.
- `[ ]` **STXIO-554155** (rerank 5.138, model=match) — Vulnerability issue: A service account sa-ansible-* has Admin, Owner, or Editor 
    - _desc:_ *Misconfiguration Description:* A service account has Admin, Owner, or Editor privileges. It is recommended that no user-created service accounts have Admin, Owner or Editor 4017d9c6.internal Tags: environment:production,productlineid:0001996383,supportgroupid:0001997404,applicationid:aid019,costce
- `[ ]` **STXIO-8118569** (rerank 5.130, model=non-match) — DEV: Secret Key Rotation setup
- `[ ]` **STXIO-8232746** (rerank 5.110, model=unsure) — Compare Ranger Security Roles / Groups / User with all Ranger Instances
    - _desc:_ *Background* We have identified configuration differences between our Ranger instances in SIMU and PROD. These differences may impact authorization behaviour (policies, roles, user/group mappings, etc.) across environments.  The issue appears in setups where Ranger uses SSSD to retrieve users and
- `[ ]` **STXIO-693449** (rerank 4.903, model=non-match) — Crosscheck
    - _desc:_ We need to verify that all risks are closed by new solutions.

