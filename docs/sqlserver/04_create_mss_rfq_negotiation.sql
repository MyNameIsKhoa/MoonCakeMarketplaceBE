-- Run against the RFQ SQL Server instance (localhost,14334 in docker-compose).
-- Bootstrap only: RFQService Flyway V1 creates the tables on first startup.
-- Safe to run again; never drop or recreate an existing database.
USE [master];
GO

IF DB_ID(N'mss_rfq_negotiation') IS NULL
    EXEC(N'CREATE DATABASE [mss_rfq_negotiation]');
GO
