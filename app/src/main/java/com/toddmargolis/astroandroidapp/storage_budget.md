System Architecture Summary (No NAS)

Overview
This system is a tiered storage and compute architecture designed to support a MacBook Air (creative workstation) and a 2013 Mac Pro (OpenClaw agent system), with high-speed local networking and multi-tier data management.

Core Principles

* Separate creative workflows from agent compute workloads
* Use tiered storage for performance, cost efficiency, and reliability
* Maintain local high-speed data transfer via 10 Gb Ethernet
* Keep model inference external (DGX + API providers), local system focuses on data + orchestration

---

Hardware Components

MacBook Air (Primary Workstation)

* 512 GB internal SSD → active projects only
* External 4 TB NVMe SSD → hot storage (photo libraries, datasets, active work)
* Thunderbolt dock (~$400) → dual monitors (4K + HD) + peripherals

Mac Pro (Late 2013, OpenClaw Node)

* Runs OpenClaw agent system
* Hosts shared storage and datasets
* Connected to external archive + backup drives

Storage

* NVMe SSD (4 TB, ~$800 total with enclosure) → hot storage
* Archive HDD (~16–20 TB, ~$300) → long-term storage
* Backup HDDs (~2 drives, ~$300 total) → offline rotation backups

Networking

* 10 Gb Ethernet switch (~$300)
* 2 × Thunderbolt → 10 Gb Ethernet adapters (~$300 total)
* Router provides internet only (not used for high-speed transfer)

---

Network Topology

Internet
│
Router
│
10 Gb Switch
├── MacBook Air (via Thunderbolt 10Gb adapter)
├── Mac Pro (via Thunderbolt 10Gb adapter)
└── Future expansion (NAS / additional nodes)

---

Storage Tiers

Tier 1 — Live (MacBook Air internal SSD)

* Active editing projects
* Temporary working files

Tier 2 — Hot Storage (External NVMe SSD)

* Photo libraries
* Active datasets
* In-progress research / art projects

Tier 3 — Archive (Mac Pro external HDD)

* Completed projects
* Historical datasets
* OpenClaw datasets and outputs

Tier 4 — Backup (Offline HDDs)

* Full copies of archive + critical data
* Rotated and stored offline

---

Data Flow

1. Create / Edit
   MacBook Air internal SSD → NVMe SSD

2. Active Work
   NVMe SSD holds working datasets and media

3. Archive
   NVMe SSD → Mac Pro archive HDD

4. Backup
   Archive HDD → offline backup drives

---

OpenClaw System Role (Mac Pro)

Directory Structure

/openclaw
/datasets
/agent_memory
/logs
/outputs

/shared
/astrophotography
/art_projects
/research

Responsibilities

* Run agent workflows
* Manage datasets and embeddings
* Store logs and generated artifacts
* Serve shared storage over network

---

Model Providers

External inference sources:

* Anthropic (Claude models)
* Google (Gemini models)
* OpenAI (GPT models)
* Remote DGX system (friend-hosted inference)

Local system responsibilities:

* Data preparation
* Orchestration
* Storage of results

---

Performance Characteristics

* NVMe (local): ~2500–3000 MB/s
* 10 Gb network: ~600–900 MB/s
* HDD archive: ~150–220 MB/s

---

Estimated Budget

* NVMe + enclosure: $800
* Dock: $400
* Archive HDD: $300
* Backup drives: $300
* 10 Gb switch: $300
* 10 Gb adapters: $300

Total: ~$2,400

---

Summary

A two-node system where:

* MacBook Air handles creative work and active datasets
* Mac Pro runs OpenClaw and hosts shared/archive storage
* 10 Gb network enables high-speed data movement
* External model providers handle inference

This provides a scalable, performant, and cost-efficient foundation for both creative workflows and agent-based systems.
