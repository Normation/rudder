require_relative '../spec_helper'
describe service('cron') do
    it { should be_enabled }
end
