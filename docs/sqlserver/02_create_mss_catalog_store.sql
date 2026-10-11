-- Run against the Catalog & Store SQL Server instance (localhost,14332 in docker-compose).
-- Bootstrap only: CatalogStoreService Flyway V1 creates the tables on first startup.
-- Safe to run again; never drop or recreate an existing database.
USE [master];
GO

IF DB_ID(N'mss_catalog_store') IS NULL
    EXEC(N'CREATE DATABASE [mss_catalog_store]');
GO
