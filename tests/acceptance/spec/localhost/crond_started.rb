require_relative '../spec_helper'
describe service('crond') do
    it { should be_running }
end
