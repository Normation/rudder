require_relative '../spec_helper'
describe service('crond') do
    it { should_not be_running }
end
