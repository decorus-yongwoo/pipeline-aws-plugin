package de.taimos.pipeline.aws;

import com.amazonaws.services.codedeploy.model.DeploymentOverview;
import com.amazonaws.services.codedeploy.model.DeploymentInfo;
import com.amazonaws.services.codedeploy.model.RevisionLocation;
import com.amazonaws.services.codedeploy.model.RevisionLocationType;
import com.amazonaws.services.codedeploy.model.S3Location;
import com.amazonaws.services.codedeploy.model.BundleType;
import com.amazonaws.services.codedeploy.model.RegisterApplicationRevisionRequest;
import com.amazonaws.services.codedeploy.model.GetDeploymentRequest;
import com.amazonaws.services.codedeploy.model.CreateDeploymentRequest;
import com.amazonaws.services.codedeploy.model.CreateDeploymentResult;
import de.taimos.pipeline.aws.utils.StepUtils;
import hudson.AbortException;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import com.amazonaws.services.codedeploy.AmazonCodeDeploy;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClientBuilder;

import hudson.model.TaskListener;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * @author yongwoo.lee <brightdelusion@gmail.com>
 */
public class CodeDeployStep extends Step {
    private final String applicationName;
    private final String groupName;
    private final String config;
    private final String bucket;
    private final String path;
    private boolean success;

    /**
     *
     * @param applicationName AWS Application name (code deploy)
     * @param groupName Deployment group name
     * @param config Code deploy Config name (ruleset)
     * @param bucket S3 Artifact bucket
     * @param path S3 Artifact path
     */
    @DataBoundConstructor
    public CodeDeployStep(String applicationName, String groupName, String config, String bucket, String path) {
        this.applicationName = applicationName;
        this.groupName = groupName;
        this.config = config;
        this.bucket = bucket;
        this.path = path;
        this.success = false;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return StepUtils.requiresDefault();
        }

        @Override
        public String getFunctionName() {
            return "codeDeploy";
        }

        @Override
        @Nonnull
        public String getDisplayName() {
            return "AWS CodeDeploy";
        }
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getBucket() {
        return bucket;
    }

    public String getConfig() {
        return config;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getPath() {
        return path;
    }

    /**
     *
     * @param context context?
     * @return StepExecution
     * @throws Exception
     */
    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new CodeDeployStep.Execution(this, context);
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private static final Long POLLING_INTERVAL = 10000L;
        private static final String SUCCEEDED_STATUS = "Succeeded";
        private static final String FAILED_STATUS = "Failed";

        protected final transient CodeDeployStep step;
        protected final transient AmazonCodeDeploy client;

        /**
         * Execution constructor
         *
         * @param step
         * @param context
         */
        public Execution(CodeDeployStep step, StepContext context) {
            super(context);
            this.step = step;
            this.client = AWSClientFactory.create(AmazonCodeDeployClientBuilder.standard(), this.getContext());
        }

        /**
         *
         * @throws Exception
         */
        @Override
        protected Void run() throws Exception {
            TaskListener listener = this.getContext().get(TaskListener.class);
            listener.getLogger().format("Run CodeDeploy application: %s\n", this.step.getApplicationName());

            try {
                RevisionLocation revisionLocation = this.getRevisionLocation( this.getArtifactFromS3() );
                this.createRevision(revisionLocation);
                String deploymentId = this.createDeployment(revisionLocation);
                this.observeDeployment(deploymentId);
            } catch (Exception e) {
                listener.getLogger().println("Failed CodeDeploy process;");
                listener.getLogger().println(e.getMessage());
                e.printStackTrace(listener.getLogger());
                throw new AbortException();
            }

            return null;
        }

        /**
         * Get artifact from the s3
         *
         * @return S3 location
         */
        private S3Location getArtifactFromS3() throws Exception {
            this.getContext().get(TaskListener.class).getLogger().format("Get Artifact from S3 (Bucket: %s, Key: %s)\n", this.step.getBucket(), this.step.getPath());
            S3Location s3Location = new S3Location();
            s3Location.setBucket(this.step.getBucket());

            // TODO: BundleType 지정할 수 있게 변경
            s3Location.setBundleType(BundleType.Tgz);
            s3Location.setKey(this.step.getPath());

            return s3Location;
        }

        /**
         *
         * @param s3Location
         * @return
         */
        private RevisionLocation getRevisionLocation(S3Location s3Location) {
            RevisionLocation revisionLocation = new RevisionLocation();
            // TODO: S3 외에 Git 도 지원
            revisionLocation.setRevisionType(RevisionLocationType.S3);
            revisionLocation.setS3Location(s3Location);

            return revisionLocation;
        }

        /**
         * 새로운 Revision 을 생성한다.
         *
         * @param revisionLocation RevisionLocation
         * @throws Exception TaskListener
         */
        private void createRevision(RevisionLocation revisionLocation) throws Exception {
            this.getContext().get(TaskListener.class).getLogger().println("Create revision (register)");

            // RegisterApplicationRevisionResult 에서 뭔가 체크할 수 있는게 있을 줄 알았는데 없음.
            // 성공/실패 여부 확인하려면 다른 방식으로 해야할 것 같음.
            this.client.registerApplicationRevision(
                    new RegisterApplicationRevisionRequest()
                        .withApplicationName(this.step.getApplicationName())
                        .withRevision(revisionLocation)
                        .withDescription("Application revision registered via Jenkins")
            );
        }

        /**
         * 새로운 Deployment 를 만든다.
         *
         * @param revisionLocation RevisionLocation
         * @return AWS CodeDeploy Deployment ID
         * @throws Exception TaskListener
         */
        private String createDeployment(RevisionLocation revisionLocation) throws Exception {
            this.getContext().get(TaskListener.class).getLogger().println("Create deployment");
            CreateDeploymentResult deploymentResult = this.client.createDeployment(
                    new CreateDeploymentRequest()
                        .withDeploymentConfigName(this.step.getConfig())
                        .withDeploymentGroupName(this.step.getGroupName())
                        .withApplicationName(this.step.getApplicationName())
                        .withRevision(revisionLocation)
                        .withDescription("Jenkins AWS CodeDeploy (Deployment)")
            );

            // Deployment ID 로 나중에 상태를 관찰한다.
            // TODO: Instance 를 던지는게 좋은 방식일까?
            return deploymentResult.getDeploymentId();
        }

        /**
         * Observe
         *
         * @param deploymentId AWS CodeDeploy deployment id
         * @throws Exception
         */
        private void observeDeployment(String deploymentId) throws Exception {
            TaskListener taskListener = this.getContext().get(TaskListener.class);
            taskListener.getLogger().format("Observe deployment (deployment id: %s)\n", deploymentId);

            GetDeploymentRequest deploymentRequest = new GetDeploymentRequest();
            deploymentRequest.setDeploymentId(deploymentId);
            DeploymentInfo deploymentInfo = this.client.getDeployment(deploymentRequest).getDeploymentInfo();

            for (; ; ) {
                if (deploymentInfo == null) {
                    taskListener.getLogger().println("Deployment status unknown.");
                } else {
                    DeploymentOverview overview = deploymentInfo.getDeploymentOverview();
                    taskListener.getLogger().format("Deployment status: %s; instances: %s\n", deploymentInfo.getStatus(), overview);
                }

                deploymentInfo = this.client.getDeployment(deploymentRequest).getDeploymentInfo();
                if (SUCCEEDED_STATUS.equals(deploymentInfo.getStatus())) {
                    taskListener.getLogger().println("Deployment completed successfully");
                    break;
                } else if (FAILED_STATUS.equals(deploymentInfo.getStatus())) {
                    taskListener.getLogger().println("Deployment completed in error");
                    String errorMessage = deploymentInfo.getErrorInformation().getMessage();
                    throw new Exception("Deployment Failed: " + errorMessage);
                }

                taskListener.getLogger().println("Deployment still in progress... sleeping");
                try {
                    Thread.sleep(POLLING_INTERVAL);
                } catch (InterruptedException exception) {
                    // do something?
                }
            }
        }
    }
}
