require_relative '../spec_helper'
describe service('cron') do
    it { should_not be_enabled }
end
