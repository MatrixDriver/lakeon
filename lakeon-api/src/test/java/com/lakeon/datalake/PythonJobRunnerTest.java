package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PythonJobRunner 单元测试")
class PythonJobRunnerTest {

    @Mock KubernetesClient k8sClient;
    @Mock DatalakeJobRepository repository;
    @Mock ObsStsService obsStsService;
    @Mock NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaceOp;
    @Mock Resource<Namespace> namespaceResource;
    @Mock MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>> configMapOp;
    @Mock Resource<ConfigMap> configMapResource;

    @SuppressWarnings("rawtypes")
    MixedOperation batchNsOp;

    LakeonProperties props;
    PythonJobRunner runner;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        props.getDatalake().setCciNamespacePrefix("datalake-");
        props.getDatalake().setVkNodeSelectorKey("virtual-kubelet.io/provider");
        props.getDatalake().setVkNodeSelectorValue("cci");
        props.getObs().setBucket("lakeon-storage");

        runner = new PythonJobRunner(k8sClient, props, repository, obsStsService);

        // Stub OBS STS credentials
        when(obsStsService.getCredentials(any())).thenReturn(
                new ObsStsService.StsCredentials("ak", "sk", "token",
                        java.time.Instant.now().plusSeconds(3600)));

        // Stub namespace check: namespace exists
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName(any())).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(new Namespace());

        // Stub batch job creation
        stubBatchCreate();
    }

    @SuppressWarnings("unchecked")
    private void stubConfigMap() {
        when(k8sClient.configMaps()).thenReturn(configMapOp);
        when(configMapOp.inNamespace(any())).thenReturn((NonNamespaceOperation) configMapOp);
        when(configMapOp.resource(any(ConfigMap.class))).thenReturn(configMapResource);
        when(configMapResource.create()).thenReturn(new ConfigMap());
    }

    @SuppressWarnings("unchecked")
    private void stubBatchCreate() {
        var batchApi = mock(io.fabric8.kubernetes.client.dsl.BatchAPIGroupDSL.class);
        var v1 = mock(io.fabric8.kubernetes.client.dsl.V1BatchAPIGroupDSL.class);
        var jobs = mock(MixedOperation.class);
        batchNsOp = mock(MixedOperation.class);
        var res = mock(io.fabric8.kubernetes.client.dsl.ScalableResource.class);
        when(k8sClient.batch()).thenReturn(batchApi);
        when(batchApi.v1()).thenReturn(v1);
        when(v1.jobs()).thenReturn(jobs);
        when(jobs.inNamespace(any())).thenReturn(batchNsOp);
        when(batchNsOp.resource(any())).thenReturn(res);
        when(res.create()).thenReturn(new Job());
    }

    private DatalakeJobEntity makeJob(String id) {
        DatalakeJobEntity e = new DatalakeJobEntity();
        e.setId(id);
        e.setTenantId("t1");
        e.setName("test");
        e.setType(DatalakeJobType.PYTHON);
        e.setStatus(DatalakeJobStatus.PENDING);
        e.setSpec("{}");
        return e;
    }

    @Test
    @DisplayName("inline_script 非空时创建 ConfigMap")
    void createsConfigMapWhenInlineScriptSet() {
        stubConfigMap();

        DatalakeJobEntity job = makeJob("job-001");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setInlineScript("import os\nprint('hello')");

        runner.start(job, req);

        ArgumentCaptor<ConfigMap> cmCaptor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(configMapOp).resource(cmCaptor.capture());
        assertThat(cmCaptor.getValue().getMetadata().getName()).isEqualTo("dl-script-job-001");
        verify(configMapResource).create();
    }

    @Test
    @DisplayName("inline_script 为空时不创建 ConfigMap")
    void noConfigMapWhenInlineScriptAbsent() {
        DatalakeJobEntity job = makeJob("job-002");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");

        runner.start(job, req);

        verify(k8sClient, never()).configMaps();
    }

    @Test
    @DisplayName("output_path 为空时自动生成 OUTPUT_PATH 环境变量")
    @SuppressWarnings("unchecked")
    void autoGeneratesOutputPathWhenAbsent() {
        DatalakeJobEntity job = makeJob("job-003");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");

        runner.start(job, req);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(batchNsOp).resource(jobCaptor.capture());
        Job captured = jobCaptor.getValue();
        var envVars = captured.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        assertThat(envVars)
            .filteredOn(e -> "OUTPUT_PATH".equals(e.getName()))
            .singleElement()
            .extracting(io.fabric8.kubernetes.api.model.EnvVar::getValue)
            .isEqualTo("s3://lakeon-storage/tenant-t1/jobs/job-003/output/");
    }

    @Test
    @DisplayName("retry_count 映射到 K8s Job backoffLimit")
    @SuppressWarnings("unchecked")
    void retryCountMapsToBackoffLimit() {
        DatalakeJobEntity job = makeJob("job-004");
        DatalakeJobRequest req = new DatalakeJobRequest();
        req.setName("test");
        req.setType(DatalakeJobType.PYTHON);
        req.setEntrypoint("python main.py");
        req.setRetryCount(2);

        runner.start(job, req);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(batchNsOp).resource(jobCaptor.capture());
        Job captured = jobCaptor.getValue();
        assertThat(captured.getSpec().getBackoffLimit()).isEqualTo(2);
    }
}
