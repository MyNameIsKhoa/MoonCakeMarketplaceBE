-- Run against the Identity SQL Server instance (localhost,14331 in docker-compose).
-- Bootstrap only: IdentityService Flyway V1 creates the tables on first startup.
-- Safe to run again; never drop or recreate an existing database.
USE [master];
GO

IF DB_ID(N'mss_identity') IS NULL
    EXEC(N'CREATE DATABASE [mss_identity]');
GO
