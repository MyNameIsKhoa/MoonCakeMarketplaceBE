-- Run against the Commerce SQL Server instance (localhost,14333 in docker-compose).
-- Bootstrap only: CommerceService Flyway V1 creates the tables on first startup.
-- Safe to run again; never drop or recreate an existing database.
USE [master];
GO

IF DB_ID(N'mss_commerce') IS NULL
    EXEC(N'CREATE DATABASE [mss_commerce]');
GO
