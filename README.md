# Labor Data Synchronization System

A Java/Vue system for project workspaces, labor master data, attendance collection, matching, controlled integration, retries, and audit records.

## Structure

- `labor-admin/`: Vue 3 + TypeScript administration frontend
- `labor-server/`: Spring Boot backend with migration scripts and mock adapters

## Local development

Set database, token, encryption, and integration values through environment variables. Use the mock integration adapter for local development and tests. Do not commit credentials, real personnel records, attendance data, device endpoints, or platform payloads.

Production snapshots, certificates, deployment configuration, templates containing business data, and customer-specific documentation are excluded from this public preparation copy.

## Vendor integration

The public copy does not bundle Hikvision Artemis binaries. Build the core modules with an authorized SDK supplied in a private environment, or disable the vendor adapter for local work.
