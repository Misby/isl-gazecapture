#!/usr/bin/python


import argparse
import json
import os
import random
import shutil

import cv2

import numpy as np

import tensorflow as tf

from data_processing import frame_randomizer, records_output, session
from itracker.common.eye_cropper import EyeCropper


class GazecapSaver(records_output.Saver):
  """ Saver specialization for Gazecapture. """

  def _interpret_label_features(self, bytes_features, float_features,
                                int_features):
    dots, face_size, leye, reye, grid = float_features[:5]
    pose = None
    if len(float_features) > 5:
      # We have pose.
      pose = float_features[5]
    session_num = int_features[0]

    features = {"dots": dots,
                "face_size": face_size,
                "leye_box": leye,
                "reye_box": reye,
                "grid_box": grid,
                "session_num": session_num}
    if pose is not None:
      features["pose"] = pose

    return features


class GazecapSession(session.Session):
  """ Session specialization for Gazecapture. """

  # Singleton estimator in case we need to compute pose.
  _lm_estimator = None

  def __init__(self, **kwargs):
    # Check if we should extract the pose.
    self.__use_pose = kwargs.get("use_pose", False)

    # Create eye cropper if necessary.
    if (self.__use_pose and GazecapSession._lm_estimator is None):
      GazecapSession._lm_estimator = EyeCropper()

    super(GazecapSession, self).__init__(**kwargs)

  def __precompute_pose(self):
    """ Precomputes the pose estimation for all images in the session before
    shuffling. The idea here is that the landmark estimation is much faster
    when it can "track" frames from a sequence, so it's worth the extra
    overhead incurred by loading images twice. """
    print "Precomputing head poses for %s..." % (self.frame_dir)

    percent_complete = 0.0
    poses = []

    for i, frame in enumerate(self.frame_files):
      if not self.valid[i]:
        # Add placeholder for invalid frame.
        poses.append(np.array([0.0, 0.0, 0.0]))
        continue

      # Load the face crop.
      bbox = self.face_bboxes[i]
      crop = self._load_crop(frame, bbox)

      # Run pose estimation.
      GazecapSession._lm_estimator.detect(crop)
      head_pose = GazecapSession._lm_estimator.estimate_pose()
      head_pose = np.squeeze(head_pose)
      poses.append(head_pose)

      # Calculate percent complete.
      new_percent = float(i) / len(self.frame_files) * 100
      if new_percent - percent_complete > 0.01:
        print "Precomputing head pose. (%.2f%% done)" % (new_percent)
        percent_complete = new_percent

    # Generate a head pose feature.
    pose_feature = np.stack(poses)
    self.float_features.append(pose_feature)

  def shuffle(self):
    # Check if we need to precompute the headpose data.
    if self.__use_pose:
      self.__precompute_pose()

    super(GazecapSession, self).shuffle()


def extract_crop_data(crop_info):
  """ Extracts the crop bounding data from the raw structure.
  Args:
    crop_info: The raw crop data structure.
  Returns:
    The x and y coordinates of the top left corner, and width and height of
    the crop, and whether the crop is valid. """
  x_crop = crop_info["X"]
  y_crop = crop_info["Y"]
  w_crop = crop_info["W"]
  h_crop = crop_info["H"]
  crop_valid = crop_info["IsValid"]

  x_crop = np.asarray(x_crop, dtype=np.float32)
  y_crop = np.asarray(y_crop, dtype=np.float32)
  w_crop = np.asarray(w_crop, dtype=np.float32)
  h_crop = np.asarray(h_crop, dtype=np.float32)
  crop_valid = np.asarray(crop_valid, dtype=np.float32)

  return x_crop, y_crop, w_crop, h_crop, crop_valid

def generate_label_features(dot_info, grid_info, face_info, left_eye_info,
                            right_eye_info, session_num):
  """ Generates raw label features for a set of data.
  Args:
    dot_info: The loaded dot information.
    grid_info: The loaded face grid information.
    face_info: The loaded face crop information.
    left_eye_info: The loaded left eye crop information.
    right_eye_info: The loaded right eye crop information.
    session_num: The session number that this data comes from.
  Returns:
    Generated bytes features, generated float features, generated int features,
    and valid indicator list. """
  # Location of the dot.
  x_cam = np.asarray(dot_info["XCam"], dtype=np.float32)
  y_cam = np.asarray(dot_info["YCam"], dtype=np.float32)

  # Crop coordinates and sizes.
  _, _, w_face, h_face, face_valid = extract_crop_data(face_info)
  x_leye, y_leye, w_leye, h_leye, leye_valid = extract_crop_data(left_eye_info)
  x_reye, y_reye, w_reye, h_reye, reye_valid = extract_crop_data(right_eye_info)
  # Face grid coordinates and sizes.
  x_grid, y_grid, w_grid, h_grid, grid_valid = extract_crop_data(grid_info)

  # Coerce face sizes to not have zeros for the invalid images so that division
  # works.
  w_face = np.clip(w_face, 1, None)
  h_face = np.clip(h_face, 1, None)

  # Convert everything to frame fractions.
  x_leye /= w_face
  y_leye /= h_face
  w_leye /= w_face
  h_leye /= h_face

  x_reye /= w_face
  y_reye /= h_face
  w_reye /= w_face
  h_reye /= h_face

  x_grid /= 25.0
  y_grid /= 25.0
  w_grid /= 25.0
  h_grid /= 25.0

  # Fuse arrays.
  dots = np.stack([x_cam, y_cam], axis=1)
  face_size = np.stack([w_face, h_face], axis=1)
  leye_boxes = np.stack([x_leye, y_leye, w_leye, h_leye], axis=1)
  reye_boxes = np.stack([x_reye, y_reye, w_reye, h_reye], axis=1)
  grid_boxes = np.stack([x_grid, y_grid, w_grid, h_grid], axis=1)

  # Group features.
  bytes_features = []
  float_features = [dots, face_size, leye_boxes, reye_boxes, grid_boxes]
  int_features = [np.asarray([[session_num]] * dots.shape[0])]

  # Generate valid array.
  valid = np.logical_and(np.logical_and(face_valid, grid_valid),
                         np.logical_and(leye_valid, reye_valid))

  return (bytes_features, float_features, int_features, valid)

def process_session(session_dir, randomizers, use_pose=False,
                    val_only=False):
  """ Process a session worth of data.
  Args:
    session_dir: The directory of the session.
    randomizers: A dictionary mapping set names to the corresponding
                 FrameRandomizer.
    use_pose: Whether to include head pose data or not.
    val_only: Whether to ignore sessions that are not marked as validation.
  Returns:
    True if it saved some valid data, false if there was no valid data. """
  # Load all the relevant metadata.
  leye_file = file(os.path.join(session_dir, "appleLeftEye.json"))
  leye_info = json.load(leye_file)
  leye_file.close()

  reye_file = file(os.path.join(session_dir, "appleRightEye.json"))
  reye_info = json.load(reye_file)
  reye_file.close()

  face_file = file(os.path.join(session_dir, "appleFace.json"))
  face_info = json.load(face_file)
  face_file.close()

  dot_file = file(os.path.join(session_dir, "dotInfo.json"))
  dot_info = json.load(dot_file)
  dot_file.close()

  grid_file = file(os.path.join(session_dir, "faceGrid.json"))
  grid_info = json.load(grid_file)
  grid_file.close()

  frame_file = file(os.path.join(session_dir, "frames.json"))
  frame_info = json.load(frame_file)
  frame_file.close()

  info_file = file(os.path.join(session_dir, "info.json"))
  general_info = json.load(info_file)
  info_file.close()

  # Generate label features.
  session_num = int(session_dir.split("/")[-1])
  bytes_f, float_f, int_f, valid = generate_label_features(dot_info, grid_info,
                                                           face_info,
                                                           leye_info,
                                                           reye_info,
                                                           session_num)

  # Check if we have any valid data from this session.
  for image in valid:
    if image:
      break
  else:
    # No valid data, no point in continuing.
    return False

  # Find the correct randomizer.
  split = general_info["Dataset"]
  if (val_only and split != "val"):
    # Not a validation session.
    return False
  randomizer = randomizers[split]

  # Calculate face bounding boxes.
  face_bboxes = extract_crop_data(face_info)
  face_bboxes = np.stack(face_bboxes, axis=1)

  # Add it to the randomizer.
  frame_dir = os.path.join(session_dir, "frames")
  my_session = GazecapSession(frame_dir=frame_dir, frame_files=frame_info,
                              valid=valid, face_bboxes=face_bboxes,
                              bytes_features=bytes_f, float_features=float_f,
                              int_features=int_f, use_pose=use_pose)
  randomizer.add_session(my_session)

  return True

def process_dataset(dataset_dir, output_dir, start_at=None, use_pose=False,
                    val_only=False):
  """ Processes an entire dataset, one session at a time.
  Args:
    dataset_dir: The root dataset directory.
    output_dir: Where to write the output data.
    start_at: Session to start at.
    use_pose: If true, will include the estimated head pose as a feature.
    val_only: If true, will only generate the validation dataset. """
  # Create output directory.
  if not start_at:
    if os.path.exists(output_dir):
      # Remove existing direcory if it exists.
      print "Removing existing directory '%s'." % (output_dir)
      shutil.rmtree(output_dir)
    os.mkdir(output_dir)

  num_test = 0
  num_val = 0

  # Create writers for writing output.
  train_record = os.path.join(output_dir, "gazecapture_train.tfrecord")
  test_record = os.path.join(output_dir, "gazecapture_test.tfrecord")
  val_record = os.path.join(output_dir, "gazecapture_val.tfrecord")
  train_writer = tf.python_io.TFRecordWriter(train_record)
  test_writer = tf.python_io.TFRecordWriter(test_record)
  val_writer = tf.python_io.TFRecordWriter(val_record)

  # Create randomizers for each split.
  train_randomizer = frame_randomizer.FrameRandomizer()
  test_randomizer = frame_randomizer.FrameRandomizer()
  val_randomizer = frame_randomizer.FrameRandomizer()

  # Group them by dataset name.
  randomizers = {"train": train_randomizer, "test": test_randomizer,
                 "val": val_randomizer}

  # Create savers for managing output writing.
  train_saver = GazecapSaver(train_randomizer, "train", train_writer)
  test_saver = GazecapSaver(test_randomizer, "test", test_writer)
  val_saver = GazecapSaver(val_randomizer, "val", val_writer)

  sessions = os.listdir(dataset_dir)

  # Process each session one by one.
  process = False
  for i, item in enumerate(sessions):
    item_path = os.path.join(dataset_dir, item)
    if not os.path.isdir(item_path):
      # This is some extraneous file.
      continue

    if (start_at and item == start_at):
      # We can start here.
      process = True

    # Print percentage complete.
    percent = float(i) / len(sessions) * 100
    print "Analyzing dataset. (%.2f%% done)" % (percent)

    process_session(item_path, randomizers, use_pose=use_pose,
                    val_only=val_only)

  # Write out everything.
  val_saver.save_all()
  if not val_only:
    test_saver.save_all()
    train_saver.save_all()

  train_saver.close()
  test_saver.close()
  val_saver.close()

def main():
  parser = argparse.ArgumentParser("Convert the GazeCapture dataset.")
  parser.add_argument("dataset_dir", help="The root dataset directory.")
  parser.add_argument("output_dir",
                      help="The directory to write output images.")
  parser.add_argument("-s", "--start_at", default=None,
                      help="Specify a session to start processing at.")
  parser.add_argument("-p", "--pose", action="store_true",
                      help="Estimate and include the head pose.")
  parser.add_argument("-v", "--val_only", action="store_true",
                      help="Only generate the validation dataset.")
  args = parser.parse_args()

  process_dataset(args.dataset_dir, args.output_dir, args.start_at,
                  args.pose, args.val_only)

if __name__ == "__main__":
  main()
