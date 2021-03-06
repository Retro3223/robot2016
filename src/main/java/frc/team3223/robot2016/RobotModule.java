package frc.team3223.robot2016;

import edu.wpi.first.wpilibj.tables.ITable;
import edu.wpi.first.wpilibj.tables.ITableListener;
import frc.team3223.autonomous.DriveToHighGoal;
import frc.team3223.autonomous.IAutonomous;
import frc.team3223.drive.*;
import frc.team3223.util.TimeProvider;
import frc.team3223.util.ToggleButton;
import jaci.openrio.toast.core.ToastBootstrap;
import jaci.openrio.toast.lib.log.Logger;
import jaci.openrio.toast.lib.module.IterativeModule;
import edu.wpi.first.wpilibj.networktables.NetworkTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RobotModule extends IterativeModule implements ITableListener {

  public static Logger logger;

  NetworkTable networkTable;
  DriveMode driveMode = DriveMode.SimpleTank;
  DriveMode lastDriveMode = DriveMode.SimpleTank;
  SimpleDrive simpleDrive;
  PolarTankDrive ptDrive;
  RotateToAngle rotateToAngle;
  Map<DriveMode, IDrive> driveModes;
  Map<AutonomousMode, IAutonomous> autonomousModes;
  DriveToHighGoal driveToHighGoal;
  AutonomousMode currentAutonomousMode;
  SillyAimAssist aimAssist;
  RobotConfiguration conf;
  Recorder recorder;
  Replayer replayer;
  RotationProfiler rotationProfiler;
  HighGoalStates highGoalState;

  long highGoalStateStartTime;

  boolean rotating = false;
  double rotateAngle;

  boolean inRecordingMode = false;
  String recordingName = "recorded";

  Shooter shooter;
  ArrayList<ToggleButton> toggleButtons;
  boolean shouldRotate = false;

  double desiredHeading = 0.00;
  int desiredEncoderValue;

  long clock;

  double target_dist = 1000;
  double target_theta = 1000;
  double target_theta_v = 1000;

  @Override
  public String getModuleName() {
    return "robot2016";
  }

  @Override
  public String getModuleVersion() {
    return "0.0.1";
  }

  @Override
  public void robotInit() {
    logger = new Logger("robot2016", Logger.ATTR_DEFAULT);
    networkTable = NetworkTable.getTable("SmartDashboard");
    networkTable.addTableListener(this);
    toggleButtons = new ArrayList<>();
    driveModes = new HashMap<>();
    autonomousModes = new HashMap<>();
    currentAutonomousMode = AutonomousMode.DriveToHighGoal;
    conf = new RobotConfiguration(networkTable);
    recorder = new Recorder(conf);
    replayer = new Replayer(conf);

    initShooter();
    initSimpleDrive();
    initRotateToAngle();
    initPolarDrive();
    initAimAssist();
    initDriveToHighGoal();

    ToggleButton recordbtn = conf.makeRecordToggle();
    recordbtn.onToggleOn((jb, n) -> {
      if (!recorder.isRecording()) {
        System.out.println("begin recording!");
        recorder.setup(recordingName);
        recorder.startRecording();
      }
    });

    toggleButtons.add(recordbtn);

    ToggleButton holdShooterUpToggle = conf.makeHoldShooterUpToggle();
    holdShooterUpToggle.onToggleOn((jb, n) -> {
      shooter.toggleHoldMode();
    });
    toggleButtons.add(holdShooterUpToggle);

    conf.publishJoystickConfiguration();
  }

  private void initAimAssist() {
    aimAssist = new SillyAimAssist(conf);
    driveModes.put(DriveMode.AimAssist, aimAssist);
    ToggleButton toggle = conf.makeAimAssistToggle();
    toggle.onToggleOn((j, b) -> {
      pushDriveMode(DriveMode.AimAssist);
    });
    toggle.onToggleOff((j, b) -> {
      revertDriveMode();
    });
    toggleButtons.add(toggle);
  }

  private void initDriveToHighGoal() {
    this.driveToHighGoal = new DriveToHighGoal(1, ptDrive, 1);
    driveToHighGoal.disable();
    autonomousModes.put(AutonomousMode.DriveToHighGoal, driveToHighGoal);
  }

  private void initShooter() {
    shooter = new Shooter(conf, networkTable);
    networkTable.addTableListener(shooter);
  }

  private void initSimpleDrive() {
    simpleDrive = new SimpleDrive(this.conf, networkTable);
    driveModes.put(DriveMode.SimpleTank, simpleDrive);
    ToggleButton toggle = conf.makeSimpleDriveResetToggle();
    toggle.onToggleOn((j, b) -> {
      lastDriveMode = driveMode = DriveMode.SimpleTank;
    });
    toggleButtons.add(toggle);

    toggle = conf.makeSimpleDriveReverseToggle();
    toggle.onToggleOn((j, b) -> {
      simpleDrive.toggleNormalJoystickOrientation();
    });
    toggleButtons.add(toggle);
  }

  private void initRotateToAngle() {
    rotateToAngle = new RotateToAngle(conf.getSensorManager().getNavX(), simpleDrive);
    networkTable.addTableListener(rotateToAngle);
    toggleButtons.add(conf.makeRotateToAngleToggle().onToggleOn((j, b) -> {
      pushDriveMode(DriveMode.RotateToAngle);
    }).onToggleOff((j, b) -> {
      revertDriveMode();
    }));
    driveModes.put(DriveMode.RotateToAngle, rotateToAngle);
  }

  private void initPolarDrive() {
    ptDrive = new PolarTankDrive(conf.getSensorManager().getNavX(), this.conf, this.networkTable);
    ptDrive.setDirectionJoystick(conf.getLeftJoystick());
    toggleButtons.add(conf.makePolarDriveToggle().onToggleOn((j, b) -> {
      if (driveMode == DriveMode.PolarFCTank) {
        revertDriveMode();
      } else {
        pushDriveMode(DriveMode.PolarFCTank);
      }
    }));
    driveModes.put(DriveMode.PolarFCTank, ptDrive);
  }

  private void revertDriveMode() {
    disableDriveModes();
    driveMode = lastDriveMode;
    driveModes.get(driveMode).enable();
  }

  private void pushDriveMode(DriveMode driveMode) {
    disableDriveModes();
    lastDriveMode = this.driveMode;
    this.driveMode = driveMode;
    driveModes.get(driveMode).enable();
  }

  private void disableDriveModes() {
    driveModes.values().forEach(dr -> {
      dr.disable();
    });
  }

  private void disableAutonomousModes() {
    autonomousModes.values().forEach(au -> {
      au.disable();
    });
  }

  public static boolean isReal() {
    return !ToastBootstrap.isSimulation;
  }

  long autoBegin;

  @Override
  public void autonomousInit() {
    shooter.publishValues();
    conf.publishJoystickConfiguration();
    this.clock = System.currentTimeMillis();
    // tell raspi to begin logging sensor data
    networkTable.putNumber("autonomousBegin", autoBegin++);

    // replayer.setup("auto");
    // replayer.start();
    autoBegin = System.currentTimeMillis();
    recorder.setup("test");
    recorder.context.add("p_velocity", () -> rotationProfiler.getVelocity());
    recorder.startRecording();
    rotationProfiler = new RotationProfiler(new TimeProvider());
    rotationProfiler.compute(-90);
    rotationProfiler.start();
    highGoalState = HighGoalStates.START;
  }

  @Override
  public void autonomousPeriodic() {
    driveBackwardsAuto();
  }

  public void driveBackwardsAuto() {
    long now = System.currentTimeMillis();

    long elapsed = now - autoBegin;

    if (elapsed < 100) {
      shooter.offBearingShooter();
    } else {
      shooter.lowerShooter();
    }
    if (500 < elapsed && elapsed < 3500) {
      simpleDrive.driveBackwards(.75);
    } else {
      simpleDrive.drive(0, 0);
    }
  }

  public void setState(HighGoalStates state) {
    this.highGoalState = state;
  }

  public void setStateAndStart(HighGoalStates state, long currentTime) {
    setState(state);
    this.highGoalStateStartTime = currentTime;
  }

  public int distanceToEncoderValue(double distance) {
    double[][] vSignalMapping = new double[][] {
            new double[] {2000, -5.0},
            new double[] {3000, -70.0}
    };
    double encoderValue = 0;
    boolean broken = false;

    if (distance < vSignalMapping[0][0]) {
      return 0;
    }
    for (int i = 1; i < vSignalMapping.length; i++) {
      double distance1 = vSignalMapping[i - 1][0];
      double distance2 = vSignalMapping[i][0];
      double encoderValue1 = vSignalMapping[i - 1][1];
      double encoderValue2 = vSignalMapping[i][1];
      if (distance <= distance2) {
        encoderValue = encoderValue1 + (encoderValue2 - encoderValue1) * (distance - distance1) / (distance2 - distance1);
        broken = true;
        break;
      }
    }
    if (!broken) {
      encoderValue = -171;
    }

    return (int) encoderValue;
  }

  public void highGoalAuto() {
    long now = System.currentTimeMillis();

    long elapsed = now - autoBegin;

    if (highGoalState == HighGoalStates.START) {
      simpleDrive.drive(0, 0);
      if (elapsed > 500) {
        this.setStateAndStart(HighGoalStates.DRIVING_BACKWARDS, now);
      }
    } else if (highGoalState == HighGoalStates.DRIVING_BACKWARDS) {
      simpleDrive.driveBackwards(.75);
      if (elapsed > 3500) {
        this.setStateAndStart(HighGoalStates.CALCULATE_ROTATION, now);
      }
    } else if (highGoalState == HighGoalStates.CALCULATE_ROTATION) {
      rotationProfiler.start();
      this.setStateAndStart(HighGoalStates.ROTATING, now);
    } else if (highGoalState == HighGoalStates.ROTATING) {
      rotationProfiler.drive(simpleDrive);
      if (rotationProfiler.isDone()) {
        this.setStateAndStart(HighGoalStates.CALCULATE_ROTATION_CORRECTION, now);
      }
    } else if (highGoalState == HighGoalStates.CALCULATE_ROTATION_CORRECTION) {
      double heading = conf.getSensorManager().getNavX().getAngle();
      this.desiredHeading = heading + target_theta;
      //if can't see target, done
      if (target_theta > -30 && target_theta < 30) {
        this.setStateAndStart(HighGoalStates.DONE, now);
      } else {
        this.setStateAndStart(HighGoalStates.ROTATE_TO_TARGET, now);
      }
    } else if (highGoalState == HighGoalStates.ROTATE_TO_TARGET){
      double heading = conf.getSensorManager().getNavX().getAngle();
      double relativeHeading = desiredHeading - heading;
      if (-1.5 < relativeHeading && relativeHeading < 1.5) {
        this.setStateAndStart(HighGoalStates.RAISE_AND_DROP_SHOOTER_INIT, now);
      } else {
        if (relativeHeading > 0) {
          rotationProfiler.rotate(2, simpleDrive);
        } else {
          rotationProfiler.rotate(-2, simpleDrive);
        }
      }
    } else if (highGoalState == HighGoalStates.RAISE_AND_DROP_SHOOTER_INIT) {
      desiredEncoderValue = distanceToEncoderValue(target_dist);
      if (conf.getShooterPitch() > desiredEncoderValue) {
        this.setStateAndStart(HighGoalStates.DROP_SHOOTER, now);
      } else {
        this.setStateAndStart(HighGoalStates.RAISE_SHOOTER, now);
      }
    } else if (highGoalState == HighGoalStates.DROP_SHOOTER) {
      shooter.lowerShooter();
      if (conf.getShooterPitch() < desiredEncoderValue) {
        this.setStateAndStart(HighGoalStates.RAISE_SHOOTER, now);
      }
    } else if (highGoalState == HighGoalStates.RAISE_SHOOTER) {
      shooter.raiseShooter();
      if (conf.getShooterPitch() > desiredEncoderValue) {
        this.setStateAndStart(HighGoalStates.SHOOTING_INIT, now);
      }
    } else if (highGoalState == HighGoalStates.SHOOTING_INIT) {
      shooter.getShootStateMachine().setStateAndStart(ShootStateMachine.State.SHOOTING_INIT, now);
      this.setStateAndStart(HighGoalStates.SHOOTING, now);
    } else if (highGoalState == HighGoalStates.SHOOTING) {
      shooter.getShootStateMachine().periodic();
      if (shooter.getShootStateMachine().getState().equals(ShootStateMachine.State.IDLE)) {
        this.setStateAndStart(HighGoalStates.DONE, now);
      }
    } else {
      simpleDrive.drive(0, 0);
    }

  }

  public void portcullisAuto() {
    long now = System.currentTimeMillis();

    if (now - autoBegin < 1000) {
      shooter.lowerShooter();
    } else {
      shooter.stopRaiser();
    }
    if (now - autoBegin > 1000 && now - autoBegin < 4500) {
      simpleDrive.driveForwards(.65);
    } else {
      simpleDrive.drive(0, 0);
    }
  }

  public void lowbarAuto() {
    long now = System.currentTimeMillis();
    long e = now - autoBegin;

    if (e < 1000) {
      shooter.lowerShooter();
    } else {
      shooter.stopRaiser();
    }
    if (e > 1000 && e < 4500) {
      simpleDrive.driveForwards(.65);
    } else {
      simpleDrive.drive(0, 0);
    }
  }

  @Override
  public void teleopInit() {
    conf.publishJoystickConfiguration();
    pushDriveMode(DriveMode.SimpleTank);
    simpleDrive.drive(0, 0);
  }

  @Override
  public void teleopPeriodic() {
    publishState();

    if (recorder.isRecording()) {
      recorder.record();
    }
    if (conf.shouldResetEncoder()) {
      conf.getSensorManager().getShooterRaiserEncoder().reset();
    }
    networkTable.putString("recordStatus", recorder.getStatusLabel());

    conf.toggleButtonsPeriodic();
    toggleButtons.forEach(tb -> tb.periodic());

    shooter.teleopPeriodic();

    if (rotating) {
      simpleDrive.rotate(.75);
      if (Math.abs(PolarTankDrive
          .normalizeDegrees(conf.getSensorManager().getNavX().getAngle() - rotateAngle)) < 3) {
        rotating = false;
      }
    } else {

      switch (driveMode) {
        case SimpleTank:
          simpleDrive.drive();
          break;
        case RotateToAngle:
          // rotateToAngle.rotate();
          break;
        case PolarFCTank:
          // ptDrive.driveSingleFieldCentric();
          break;
        case PolarFCTankRev:
          // ptDriveRev.driveSingleFieldCentric();
          break;
        case AimAssist:
          // aimAssist.drive();
          break;
      }
    }
  }



  @Override
  public void testInit() {
    conf.publishTestJoystickConfiguration();
  }

  @Override
  public void testPeriodic() {
    conf.toggleButtonsPeriodic();
    publishState();

    if (conf.shouldResetEncoder()) {
      conf.getSensorManager().getShooterRaiserEncoder().reset();
    }

    // left cam
    if (conf.testShouldAimUpLeft()) {
      shooter.raiseShooterLeft();
    } else if (conf.testShouldAimDownLeft()) {
      shooter.lowerShooterLeft();
    } else if (conf.testShouldOffBearingLeft()) {
      shooter.offBearingShooterLeft();
    } else if (conf.testShouldStayLeft()) {
      shooter.holdRaiserLeft();
    } else {
      shooter.stopRaiserLeft();
    }

    // right cam
    if (conf.testShouldAimUpRight()) {
      shooter.raiseShooterRight();
    } else if (conf.testShouldAimDownRight()) {
      shooter.lowerShooterRight();
    } else if (conf.testShouldOffBearingRight()) {
      shooter.offBearingShooterRight();
    } else if (conf.testShouldStayRight()) {
      shooter.holdRaiserRight();
    } else {
      shooter.stopRaiserRight();
    }

    // left shooter
    if (conf.testShouldSlurpLeft()) {
      shooter.slurpLeft();
    } else if (conf.testShouldShootLeft()) {
      shooter.shootLeft();
    } else {
      shooter.stopShooterLeft();
    }

    // right shooter
    if (conf.testShouldSlurpRight()) {
      shooter.slurpRight();
    } else if (conf.testShouldShootRight()) {
      shooter.shootRight();
    } else {
      shooter.stopShooterRight();
    }
  }

  @Override
  public void disabledPeriodic() {
    conf.toggleButtonsPeriodic();
    publishState();
  }


  public void publishState() {
    if (isReal()) {
      networkTable.putNumber("angle", conf.getSensorManager().getNavX().getAngle());
      networkTable.putNumber("dangle", conf.getSensorManager().getNavX().getRate());
      networkTable.putNumber("yaw", conf.getSensorManager().getNavX().getYaw());
      networkTable.putNumber("pitch", conf.getSensorManager().getNavX().getPitch());
      networkTable.putNumber("roll", conf.getSensorManager().getNavX().getRoll());
      networkTable.putNumber("accel_x", conf.getSensorManager().getNavX().getWorldLinearAccelX());
      networkTable.putNumber("accel_y", conf.getSensorManager().getNavX().getWorldLinearAccelY());
      networkTable.putNumber("accel_z", conf.getSensorManager().getNavX().getWorldLinearAccelZ());
      networkTable.putNumber("velocity_x", conf.getSensorManager().getNavX().getVelocityX());
      networkTable.putNumber("velocity_y", conf.getSensorManager().getNavX().getVelocityY());
      networkTable.putNumber("velocity_z", conf.getSensorManager().getNavX().getVelocityZ());
      networkTable.putNumber("pos_x", conf.getSensorManager().getNavX().getDisplacementX());
      networkTable.putNumber("pos_y", conf.getSensorManager().getNavX().getDisplacementY());
      networkTable.putNumber("pos_z", conf.getSensorManager().getNavX().getDisplacementZ());
      networkTable.putNumber("fused_heading", conf.getSensorManager().getNavX().getFusedHeading());
      networkTable.putNumber("shooter_pitch", conf.getShooterPitch());
    }
    networkTable.putString("driveMode", driveMode.toString());
    networkTable.putBoolean("tongueLimit", conf.isTongueBack());
  }

  @Override
  public void valueChanged(ITable table, String name, Object value, boolean isNew) {
    System.out.println("received " + name + ": " + value);
    switch (name) {
      case "recordMode": {
        System.out.println("recordMode=" + inRecordingMode + ", recording to auto");
        inRecordingMode = (boolean) value;
        recordingName = "auto";
        recorder.setup(recordingName);
        break;
      }
      case "recordName": {
        try {
          recordingName = (String) value;
          System.out.println("recordName=" + recordingName);
          recorder.setup(recordingName);
        } catch (Exception exception) {
          exception.printStackTrace();
        }
        break;
      }
      case "recording": {
        boolean recording = (boolean) value;
        if (!recorder.isRecording() && recording) {
          System.out.println("recording begins!");
          inRecordingMode = true;
          try {
            recorder.startRecording();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        break;
      }
      case "target_dist": {
        target_dist = (double) value;
        break;
      }
      case "target_theta": {
        target_theta = (double) value;
        break;
      }
      case "target_theta_v": {
        target_theta_v = (double) value;
        break;
      }
    }
  }
}
