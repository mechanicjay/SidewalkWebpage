/**
 * MissionProgress module.
 * Todo. Rename this... Probably some of these features should be moved to status/MissionStatus.js
 * @returns {{className: string}}
 * @constructor
 * @memberof svl
 */
function MissionProgress (svl, gameEffect, modalMission, modalMissionComplete, neighborhoodContainer, taskContainer) {
    var self = { className: 'MissionProgress' };
    var status = {
            currentCompletionRate: 0,
            currentMission: null,
            previousHeading: 0
        };

    function _init() {
    }

    /**
     * Finish the mission.
     * @param mission
     */
    function complete (mission) {
        if (mission) {
            mission.complete();
            svl.missionContainer.addToCompletedMissions(mission);
            svl.missionContainer.stage(mission);
        }
    }

    /**
     * @param mission Next mission
     * @param neighborhood Current neighborhood
     */
    function _showNextMission (mission, neighborhood) {
        var label = mission.getProperty("label");
        var parameters = { badgeURL: mission.getProperty("badgeURL") };

        if (label == "distance-mission") {
            parameters.distance = mission.getProperty("distance");
            modalMission.setMission(mission, neighborhood, parameters);
        } else if (label == "area-coverage-mission") {
            parameters.coverage = mission.getProperty("coverage");
            modalMission.setMissionM(mission, neighborhood, parameters);
        } else {
            console.warn("Debug: It shouldn't reach here.");
        }
    }

    /**
     * This method updates the mission completion rate and its visualization.
     */
    function update (currentMission, currentRegion) {
        if (svl && "onboarding" in svl && svl.onboarding && svl.onboarding.isOnboarding()) return;  // Don't show the mission completion message
        if ("missionContainer" in svl) {
            var completionRate;

            var _callback = function (e) {
                var currentRegionId = currentRegion.getProperty("regionId");
                var nextMission = svl.missionContainer.nextMission(currentRegionId);
                var movedToANewRegion = false;

                // Check if the next mission is null and, if so, get a mission from other neighborhood.
                // Note. Highly unlikely, but this could potentially be an infinate loop
                while (!nextMission) {
                    // If not more mission is available in the current neighborhood, get missions from the next neighborhood.
                    var availableRegionIds = svl.missionContainer.getAvailableRegionIds();
                    var newRegionId = neighborhoodContainer.getNextRegionId(currentRegionId, availableRegionIds);
                    nextMission = svl.missionContainer.nextMission(newRegionId);
                    movedToANewRegion = true;
                }

                svl.missionContainer.setCurrentMission(nextMission);
                _showNextMission(nextMission, currentRegion);

                if (movedToANewRegion) {
                    neighborhoodContainer.moveToANewRegion(newRegionId);
                    taskContainer.fetchTasksInARegion(newRegionId, function () {
                        // Jump to the new location.
                        var newTask = taskContainer.nextTask(task);
                        taskContainer.setCurrentTask(newTask);
                        svl.map.moveToTheTaskLocation(newTask);
                        
                    }, false);  // Fetch tasks in the new region
                }
            };

            // Update the mission completion rate in the progress bar
            if (currentMission) {
                completionRate = currentMission.getMissionCompletionRate();
                svl.missionStatus.printCompletionRate(completionRate);
                svl.missionStatus.updateMissionCompletionBar(completionRate);

                if (currentMission.getMissionCompletionRate() > 0.999) {
                    complete(currentMission);
                    svl.missionContainer.commit();

                    gameEffect.playAudio({audioType: "yay"});
                    gameEffect.playAudio({audioType: "applause"});

                    modalMissionComplete.update(currentMission, currentRegion);
                    modalMissionComplete.show();
                    modalMissionComplete.one('closeButton', 'click', _callback);
                }
            }
        }
    }
    
    self.update = update;
    _init();
    return self;
}
