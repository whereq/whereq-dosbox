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
             $(GUIDE_SCREENSHOTS) $(GUIDE_SCREENSHOTS_N9)

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
  screenshots/android-n8-28-game-mode-remap.png \
  screenshots/android-n8-29-keyboard-18x9-letters.png \
  screenshots/android-n8-30-keyboard-columns.png \
  screenshots/android-n8-30b-keyboard-columns-qwerty.png

# The n9 series: the complete user-guide set, taken from the release-signed
# 1.0.0.19 APK.  Every screen of the app, in the order a reader meets them,
# with one game running through all of it.
GUIDE_SCREENSHOTS_N9 = \
  screenshots/android-n9-01-files-panel-empty.png \
  screenshots/android-n9-02-picker-download.png \
  screenshots/android-n9-03-bundle-installed.png \
  screenshots/android-n9-04-notification-permission.png \
  screenshots/android-n9-05-game-running.png \
  screenshots/android-n9-06-files-panel-resume.png \
  screenshots/android-n9-07-dos-prompt.png \
  screenshots/android-n9-08-game-mode-hybrid.png \
  screenshots/android-n9-09-game-mode-profile-picker.png \
  screenshots/android-n9-10-game-mode-action.png \
  screenshots/android-n9-11-game-mode-point.png \
  screenshots/android-n9-12-game-mode-remap.png \
  screenshots/android-n9-13-game-mode-off.png \
  screenshots/android-n9-14-states-empty.png \
  screenshots/android-n9-15-saves-offer.png \
  screenshots/android-n9-16-folder-permission.png \
  screenshots/android-n9-17-files-panel-resume-slot.png \
  screenshots/android-n9-18-states-slot.png \
  screenshots/android-n9-19-settings.png \
  screenshots/android-n9-20-saves-folder.png \
  screenshots/android-n9-21-saves-folder-unset.png \
  screenshots/android-n9-22-durable-folder-contents.png \
  screenshots/android-n9-23-restore-games-offer.png \
  screenshots/android-n9-24-games-restored.png \
  screenshots/android-n9-25-states-restored-slot.png \
  screenshots/android-n9-26-keyboard-symbols.png \
  screenshots/android-n9-27-keyboard-functions.png \
  screenshots/android-n9-28-launcher-home.png \
  screenshots/android-n9-29-app-info.png \
  screenshots/android-n9-30-running-in-background.png \
  screenshots/android-n9-31-tap-to-return.png \
  screenshots/android-n9-32-narrow-18x9.png

