/**
 *  Copyright 2009, 2010 The Regents of the University of California
 *  Licensed under the Educational Community License, Version 2.0
 *  (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.osedu.org/licenses/ECL-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS"
 *  BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 *  or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 */
package org.opencastproject.ingest.impl;

import org.opencastproject.capture.CaptureParameters;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElements;
import org.opencastproject.metadata.dublincore.DublinCoreCatalog;
import org.opencastproject.metadata.dublincore.DublinCoreCatalogImpl;
import org.opencastproject.metadata.dublincore.DublinCoreCatalogService;
import org.opencastproject.scheduler.api.SchedulerService;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AclScope;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.JaxbRole;
import org.opencastproject.security.api.JaxbUser;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.TrustedHttpClient;
import org.opencastproject.security.api.User;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.series.api.SeriesService;
import org.opencastproject.serviceregistry.api.IncidentService;
import org.opencastproject.serviceregistry.api.ServiceRegistryInMemoryImpl;
import org.opencastproject.util.data.Tuple;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowDefinitionImpl;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowInstance.WorkflowState;
import org.opencastproject.workflow.api.WorkflowService;
import org.opencastproject.workingfilerepository.api.WorkingFileRepository;
import org.opencastproject.util.NotFoundException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;

public class IngestServiceImplTest {
  private IngestServiceImpl service = null;
  private DublinCoreCatalogService dublinCoreService = null;
  private SeriesService seriesService = null;
  private WorkflowService workflowService = null;
  private WorkflowInstance workflowInstance = null;
  private WorkingFileRepository wfr = null;
  private static URI baseDir;
  private static URI urlTrack;
  private static URI urlTrack1;
  private static URI urlTrack2;
  private static URI urlCatalog;
  private static URI urlCatalog1;
  private static URI urlCatalog2;
  private static URI urlAttachment;
  private static URI urlPackage;
  private static URI urlPackageOld;

  private static File ingestTempDir;
  private static File packageFile;

  private static long workflowInstanceID = 1L;

  @BeforeClass
  public static void beforeClass() throws URISyntaxException {
    baseDir = IngestServiceImplTest.class.getResource("/").toURI();
    urlTrack = IngestServiceImplTest.class.getResource("/av.mov").toURI();
    urlTrack1 = IngestServiceImplTest.class.getResource("/vonly.mov").toURI();
    urlTrack2 = IngestServiceImplTest.class.getResource("/aonly.mov").toURI();
    urlCatalog = IngestServiceImplTest.class.getResource("/mpeg-7.xml").toURI();
    urlCatalog1 = IngestServiceImplTest.class.getResource("/dublincore.xml").toURI();
    urlCatalog2 = IngestServiceImplTest.class.getResource("/series-dublincore.xml").toURI();
    urlAttachment = IngestServiceImplTest.class.getResource("/cover.png").toURI();
    urlPackage = IngestServiceImplTest.class.getResource("/data.zip").toURI();
    urlPackageOld = IngestServiceImplTest.class.getResource("/data.old.zip").toURI();

    ingestTempDir = new File(new File(baseDir), "ingest-temp");
    packageFile = new File(ingestTempDir, baseDir.relativize(urlPackage).toString());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Before
  public void setUp() throws Exception {

    FileUtils.forceMkdir(ingestTempDir);

    // set up service and mock workspace
    wfr = EasyMock.createNiceMock(WorkingFileRepository.class);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlTrack);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlAttachment);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlTrack1);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlTrack2);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog1);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog2);
    EasyMock.expect(
            wfr.put((String) EasyMock.anyObject(), (String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog);

    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlTrack1);
    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlTrack2);
    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog1);
    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog2);
    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlCatalog);

    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlPackage);

    EasyMock.expect(
            wfr.putInCollection((String) EasyMock.anyObject(), (String) EasyMock.anyObject(),
                    (InputStream) EasyMock.anyObject())).andReturn(urlPackageOld);

    workflowInstance = EasyMock.createNiceMock(WorkflowInstance.class);
    EasyMock.expect(workflowInstance.getId()).andReturn(workflowInstanceID);
    EasyMock.expect(workflowInstance.getState()).andReturn(WorkflowState.STOPPED);

    workflowService = EasyMock.createNiceMock(WorkflowService.class);
    EasyMock.expect(
            workflowService.start((WorkflowDefinition) EasyMock.anyObject(), (MediaPackage) EasyMock.anyObject(),
                    (Map) EasyMock.anyObject())).andReturn(workflowInstance);
    EasyMock.expect(
            workflowService.start((WorkflowDefinition) EasyMock.anyObject(), (MediaPackage) EasyMock.anyObject(),
                    (Map) EasyMock.anyObject())).andReturn(workflowInstance);
    EasyMock.expect(
            workflowService.start((WorkflowDefinition) EasyMock.anyObject(), (MediaPackage) EasyMock.anyObject()))
            .andReturn(workflowInstance);
    EasyMock.expect(workflowService.getWorkflowDefinitionById((String) EasyMock.anyObject())).andReturn(
            new WorkflowDefinitionImpl());
    EasyMock.expect(workflowService.getWorkflowById(EasyMock.anyLong())).andReturn(workflowInstance);

    SchedulerService schedulerService = EasyMock.createNiceMock(SchedulerService.class);

    Properties properties = new Properties();
    properties.put(CaptureParameters.INGEST_WORKFLOW_DEFINITION, "sample");
    properties.put("agent-name", "matterhorn-agent");
    EasyMock.expect(schedulerService.getEventCaptureAgentConfiguration(EasyMock.anyLong())).andReturn(properties)
            .anyTimes();
    EasyMock.expect(schedulerService.getEventDublinCore(EasyMock.anyLong()))
            .andReturn(new DublinCoreCatalogImpl(urlCatalog1.toURL().openStream())).anyTimes();

    EasyMock.replay(wfr, workflowInstance, workflowService, schedulerService);

    User anonymous = new JaxbUser("anonymous", new DefaultOrganization(), new JaxbRole(
            DefaultOrganization.DEFAULT_ORGANIZATION_ANONYMOUS, new DefaultOrganization()));
    UserDirectoryService userDirectoryService = EasyMock.createMock(UserDirectoryService.class);
    EasyMock.expect(userDirectoryService.loadUser((String) EasyMock.anyObject())).andReturn(anonymous).anyTimes();
    EasyMock.replay(userDirectoryService);

    Organization organization = new DefaultOrganization();
    OrganizationDirectoryService organizationDirectoryService = EasyMock.createMock(OrganizationDirectoryService.class);
    EasyMock.expect(organizationDirectoryService.getOrganization((String) EasyMock.anyObject()))
            .andReturn(organization).anyTimes();
    EasyMock.replay(organizationDirectoryService);

    SecurityService securityService = EasyMock.createNiceMock(SecurityService.class);
    EasyMock.expect(securityService.getUser()).andReturn(anonymous).anyTimes();
    EasyMock.expect(securityService.getOrganization()).andReturn(organization).anyTimes();
    EasyMock.replay(securityService);

    HttpEntity entity = EasyMock.createMock(HttpEntity.class);
    InputStream is = getClass().getResourceAsStream("/av.mov");
    byte[] movie = IOUtils.toByteArray(is);
    IOUtils.closeQuietly(is);
    EasyMock.expect(entity.getContent()).andReturn(new ByteArrayInputStream(movie)).anyTimes();
    EasyMock.replay(entity);

    StatusLine statusLine = EasyMock.createMock(StatusLine.class);
    EasyMock.expect(statusLine.getStatusCode()).andReturn(200).anyTimes();
    EasyMock.replay(statusLine);

    HttpResponse httpResponse = EasyMock.createMock(HttpResponse.class);
    EasyMock.expect(httpResponse.getStatusLine()).andReturn(statusLine).anyTimes();
    EasyMock.expect(httpResponse.getEntity()).andReturn(entity).anyTimes();
    EasyMock.replay(httpResponse);

    TrustedHttpClient httpClient = EasyMock.createNiceMock(TrustedHttpClient.class);
    EasyMock.expect(httpClient.execute((HttpGet) EasyMock.anyObject())).andReturn(httpResponse).anyTimes();
    EasyMock.replay(httpClient);

    AuthorizationService authorizationService = EasyMock.createNiceMock(AuthorizationService.class);
    EasyMock.expect(authorizationService.getActiveAcl((MediaPackage) EasyMock.anyObject()))
            .andReturn(Tuple.tuple(new AccessControlList(), AclScope.Series)).anyTimes();
    EasyMock.replay(authorizationService);

    service = new IngestServiceImpl();
    service.setHttpClient(httpClient);
    service.setAuthorizationService(authorizationService);
    service.setWorkingFileRepository(wfr);
    service.setWorkflowService(workflowService);
    service.setSecurityService(securityService);
    service.setSchedulerService(schedulerService);
    ServiceRegistryInMemoryImpl serviceRegistry = new ServiceRegistryInMemoryImpl(service, securityService,
            userDirectoryService, organizationDirectoryService, EasyMock.createNiceMock(IncidentService.class));
    serviceRegistry.registerService(service);
    service.setServiceRegistry(serviceRegistry);
    service.defaultWorkflowDefinionId = "sample";
    serviceRegistry.registerService(service);
  }

  @After
  public void tearDown() {
    FileUtils.deleteQuietly(ingestTempDir);
  }

  @Test
  public void testThinClient() throws Exception {
    MediaPackage mediaPackage = null;

    mediaPackage = service.createMediaPackage();
    mediaPackage = service.addTrack(urlTrack, null, mediaPackage);
    mediaPackage = service.addCatalog(urlCatalog1, MediaPackageElements.EPISODE, mediaPackage);
    mediaPackage = service.addAttachment(urlAttachment, MediaPackageElements.MEDIAPACKAGE_COVER_FLAVOR, mediaPackage);
    WorkflowInstance instance = service.ingest(mediaPackage);
    Assert.assertEquals(1, mediaPackage.getTracks().length);
    Assert.assertEquals(1, mediaPackage.getCatalogs().length);
    Assert.assertEquals(1, mediaPackage.getAttachments().length);
    Assert.assertEquals(workflowInstanceID, instance.getId());
  }

  @Test
  public void testThickClient() throws Exception {

    FileUtils.copyURLToFile(urlPackage.toURL(), packageFile);

    InputStream packageStream = null;
    try {
      packageStream = urlPackage.toURL().openStream();
      WorkflowInstance instance = service.addZippedMediaPackage(packageStream);

      // Assert.assertEquals(2, mediaPackage.getTracks().length);
      // Assert.assertEquals(3, mediaPackage.getCatalogs().length);
      Assert.assertEquals(workflowInstanceID, instance.getId());
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    } finally {
      IOUtils.closeQuietly(packageStream);
    }

  }

  @Test
  public void testThickClientOldMP() throws Exception {

    FileUtils.copyURLToFile(urlPackageOld.toURL(), packageFile);

    InputStream packageStream = null;
    try {
      packageStream = urlPackageOld.toURL().openStream();
      WorkflowInstance instance = service.addZippedMediaPackage(packageStream);

      // Assert.assertEquals(2, mediaPackage.getTracks().length);
      // Assert.assertEquals(3, mediaPackage.getCatalogs().length);
      Assert.assertEquals(workflowInstanceID, instance.getId());
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    } finally {
      IOUtils.closeQuietly(packageStream);
    }

  }

  @Test
  public void testStartOver() throws Exception {
    MediaPackage mediaPackage = null;
    Map<String, String> properties = new HashMap<String, String>();
    properties.put("archive", "true");

    mediaPackage = service.createMediaPackage();
    mediaPackage = service.addTrack(urlTrack, null, mediaPackage);
    mediaPackage = service.addAttachment(urlAttachment, MediaPackageElements.MEDIAPACKAGE_COVER_FLAVOR, mediaPackage);

    service.ingest(mediaPackage, null, properties, 121L);

    Assert.assertEquals(1, mediaPackage.getTracks().length);
    Assert.assertEquals(1, mediaPackage.getCatalogs().length);
    Assert.assertEquals(1, mediaPackage.getAttachments().length);
  }

  /**
   * Test four cases: 1) If no config file 2) If config file but no key 3) If key and false value 4) If key and true
   * value
   *
   * @throws Exception
   */
  @Test
  public void testVarySeriesOverwriteConfiguration() throws Exception {
    Boolean isOverwriteSeries;
    Dictionary<String, String> properties = new Hashtable<String, String>();

    // Test with no properties
    // NOTE: This test only works if the serivce.update() was not triggered by any previous tests
    testSeriesUpdateNewAndExisting(null);

    // Test with properties and no key
    testSeriesUpdateNewAndExisting(properties);

    // Test with properties and key is true
    isOverwriteSeries = true;
    properties.put(IngestServiceImpl.PROPKEY_OVERWRITE_SERIES, String.valueOf(isOverwriteSeries));
    testSeriesUpdateNewAndExisting(properties);

    // Test series overwrite key is false
    isOverwriteSeries = false;
    properties.put(IngestServiceImpl.PROPKEY_OVERWRITE_SERIES, String.valueOf(isOverwriteSeries));
    testSeriesUpdateNewAndExisting(properties);
  }

  /**
   * Test method for {@link org.opencastproject.ingest.impl.IngestServiceImpl#updateSeries(java.net.URI)}
   */
  private void testSeriesUpdateNewAndExisting(Dictionary<String, String> properties) throws Exception {

    // default expectation for series overwrite is True
    Boolean isExpectSeriesOverwrite = true;

    if (properties != null) {
      service.updated(properties);
      try {
        Boolean testForValue = Boolean.valueOf(((String) properties.get(IngestServiceImpl.PROPKEY_OVERWRITE_SERIES))
                .trim());
        isExpectSeriesOverwrite = testForValue;
      } catch (Exception e) {
        // If key or value not found or not boolean, use the default overwrite expectation
      }
    }

    // Get test series dublin core for the mock return value
    File catalogFile = new File(urlCatalog2);
    if (!catalogFile.exists() || !catalogFile.canRead())
      throw new Exception("Unable to access test catalog " + urlCatalog2.getPath());
    FileInputStream in = new FileInputStream(catalogFile);
    DublinCoreCatalog series = new DublinCoreCatalogImpl(in);
    IOUtils.closeQuietly(in);

    // Set dublinCore service to return test dublin core
    dublinCoreService = org.easymock.EasyMock.createNiceMock(DublinCoreCatalogService.class);
    org.easymock.EasyMock.expect(dublinCoreService.load((InputStream) EasyMock.anyObject()))
            .andReturn(series).anyTimes();
    org.easymock.EasyMock.replay(dublinCoreService);
    service.setDublinCoreService(dublinCoreService);

    // Test with mock found series
    seriesService = EasyMock.createNiceMock(SeriesService.class);
    EasyMock.expect(seriesService.getSeries((String) EasyMock.anyObject())).andReturn(series).once();
    EasyMock.expect(seriesService.updateSeries(series)).andReturn(series).once();
    EasyMock.replay(seriesService);
    service.setSeriesService(seriesService);

    // This is true or false depending on the isOverwrite value
    Assert.assertEquals("Desire to update series is " + String.valueOf(isExpectSeriesOverwrite) + ".",
            isExpectSeriesOverwrite.booleanValue(),
            service.updateSeries(urlCatalog2));

    // Test with mock not found exception
    EasyMock.reset(seriesService);
    EasyMock.expect(seriesService.updateSeries(series)).andReturn(series).once();
    EasyMock.expect(seriesService.getSeries((String) EasyMock.anyObject())).andThrow(new NotFoundException()).once();
    EasyMock.replay(seriesService);

    service.setSeriesService(seriesService);

    // This should be true, i.e. create new series, in all cases
    Assert.assertEquals("Always create a new series catalog.", true, service.updateSeries(urlCatalog2));

  }


}
