require_relative '../spec_helper'
describe service('syslogd') do
    it { should be_running }
end
