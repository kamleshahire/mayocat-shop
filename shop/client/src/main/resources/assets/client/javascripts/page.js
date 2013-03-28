'use strict'

angular.module('page', ['ngResource'])

    .controller('PageController', [
        '$scope',
        '$rootScope',
        '$routeParams',
        '$resource',
        '$http',
        '$location',
        'addonsService',
        'imageService',
        'configurationService',

        function ($scope, $rootScope, $routeParams, $resource, $http, $location, addonsService, imageService,
                  configurationService) {

            $scope.slug = $routeParams.page;

            $scope.publishPage = function () {
                $scope.page.published = true;
                $scope.updatePage();
            }

            $scope.updatePage = function () {
                if ($scope.isNew()) {
                    $http.post("/api/1.0/page/", $scope.page)
                        .success(function (data, status, headers, config) {
                            var fragments = headers("location").split('/'),
                                slug = fragments[fragments.length - 1];
                            $location.url("/page/" + slug);
                        })
                        .error(function (data, status, headers, config) {
                            // TODO handle 409 conflict
                        });
                }
                else {
                    $scope.PageResource.save({ "slug": $scope.slug }, $scope.page);
                }
            };

            $scope.editThumbnails = function (image) {
                $rootScope.$broadcast('thumbnails:edit', image);
            }

            $scope.PageResource = $resource("/api/1.0/page/:slug");

            $scope.isNew = function () {
                return $scope.slug == "_new";
            };

            $scope.newPage = function () {
                return {
                    slug: "",
                    title: "",
                    addons: []
                };
            }

            $scope.reloadImages = function () {
                $scope.page.images = $http.get("/api/1.0/page/" + $scope.slug + "/image").success(function (data) {
                    $scope.page.images = data;
                });
            }

            $scope.selectFeatureImage = function (image) {
                imageService.selectFeatured($scope.page, image);
            }

            $scope.getImageUploadUri = function () {
                return "/api/1.0/page/" + $scope.slug + "/attachment";
            }

            $scope.initializeAddons = function () {
                addonsService.initialize("page", $scope.page).then(function (addons) {
                    $scope.addons = addons;
                });
            }

            $scope.initializeModels = function() {
                $scope.models = [];
                configurationService.get("entities", function (entities) {
                    if (typeof entities.page !== 'undefined') {
                        for (var modelId in entities.page.models) {
                            if (entities.page.models.hasOwnProperty(modelId)) {
                                var model = entities.page.models[modelId];
                                $scope.models.push({
                                    id: modelId,
                                    name: model.name
                                });
                            }
                        }
                    }
                });
            }

            // Initialize existing page or new page

            if (!$scope.isNew()) {
                $scope.page = $scope.PageResource.get({
                    "slug": $scope.slug,
                    "expand": ["images"] }, function () {

                    $scope.initializeAddons();
                    $scope.initializeModels();

                    if ($scope.page.published == null) {
                        // "null" does not seem to be evaluated properly in angular directives
                        // (like ng-show="something != null")
                        // Thus, we convert "null" published flag to undefined to be able to have that "high impedance"
                        // state in angular directives.
                        $scope.page.published = undefined;
                    }
                });
            }
            else {
                $scope.page = $scope.newPage();
                $scope.initializeAddons();
                $scope.initializeModels();
            }

            $scope.confirmDeletion = function() {
                $rootScope.$broadcast('page:confirmDelete');
            }

            $scope.deletePage = function() {
                $scope.PageResource.delete({
                    "slug" : $scope.slug
                }, function(){
                    $rootScope.$broadcast('page:dismissConfirmDelete');
                    $location.url("/contents");
                });
            }

        }]);
