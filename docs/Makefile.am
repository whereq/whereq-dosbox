# Main Makefile for DOSBox

man_MANS = dosbox.1
# WhereQ-DOSBox: the technical reference that lives next to DOSBox's own
# documents. It is part of the distribution tarball so that a release carries it.
# The privacy policy goes with it: the app links to the copy in the repository,
# and a release tarball that leaves the policy behind would be a release that
# ships the app without it.
EXTRA_DIST = $(man_MANS) README.video PORTING \
             TECHNICAL-REFERENCE.md USER-GUIDE.md INTERRUPTS.md \
             PRIVACY.md \
             $(GUIDE_SCREENSHOTS)

# The screenshots the user guide points at. The guide is distributed with
# the release tarball, so the pictures it shows have to go with it.
GUIDE_SCREENSHOTS = \
  screenshots/android-n8-01-files-panel-empty.png \
  screenshots/android-n8-02-files-panel.png \
  screenshots/android-n8-02b-files-panel-resume.png \
  screenshots/android-n8-03-dos-boot-prompt.png \
  screenshots/android-n8-04-game-f1-menu.png \
  screenshots/android-n8-05-game-starfall-title.png \
  screenshots/android-n8-06-game-starfall-play.png \
  screenshots/android-n8-07-save-states-empty.png \
  screenshots/android-n8-08-saves-offer.png \
  screenshots/android-n8-09-folder-permission.png \
  screenshots/android-n8-10-files-panel-resume-slot.png \
  screenshots/android-n8-11-settings-dialog.png \
  screenshots/android-n8-12-saves-folder-dialog.png \
  screenshots/android-n8-13-save-states-slot.png \
  screenshots/android-n8-14-saves-folder-unset.png \
  screenshots/android-n8-15-durable-folder-contents.png \
  screenshots/android-n8-16-restore-games-offer.png \
  screenshots/android-n8-17-games-restored.png \
  screenshots/android-n8-18-save-states-restored-slot.png \
  screenshots/android-n8-19-notification-permission.png \
  screenshots/android-n8-20-launcher-home-icon.png \
  screenshots/android-n8-21-app-info.png \
  screenshots/android-n8-22-keyboard-symbols.png \
  screenshots/android-n8-23-keyboard-functions.png \
  screenshots/android-n8-24-game-mode-hybrid.png \
  screenshots/android-n8-25-game-mode-action.png \
  screenshots/android-n8-26-game-mode-point.png \
  screenshots/android-n8-27-game-mode-profile-picker.png \
  screenshots/android-n8-28-game-mode-remap.png

