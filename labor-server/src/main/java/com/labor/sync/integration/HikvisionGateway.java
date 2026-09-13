package com.labor.sync.integration;

import java.util.List;

public interface HikvisionGateway {
    ConnectionResult testConnection(IntegrationConfig config);
    List<HikOrganization> queryOrganizations(IntegrationConfig config);
    HikEventPage queryAttendanceEvents(IntegrationConfig config, HikEventQuery query);
}
