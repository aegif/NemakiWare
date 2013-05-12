require 'test_helper'

class CmisControllerTest < ActionController::TestCase
  test "should get index" do
    get :index
    assert_response :success
  end

  test "should get explore" do
    get :explore
    assert_response :success
  end

  test "should get download" do
    get :download
    assert_response :success
  end

end
